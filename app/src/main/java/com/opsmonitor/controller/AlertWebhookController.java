package com.opsmonitor.controller;

import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.platform.AlertCenterService;
import com.opsmonitor.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AlertManager Webhook 接收器
 *
 * v2.10 P2-07 修复:
 *   - 端点 /api/alerts/webhook 原本在 SecurityInterceptor.PUBLIC_PATHS 里(无认证)
 *   - 任何能访问 8080 的人都可以伪造告警 → 若配了 Sentinel Runbook 可能触发命令执行
 *
 * 修复策略(使用 AlertManager 官方标准):
 *   1. 请求必须带 Authorization: Bearer <secret> header
 *   2. 值与 ops-monitor.security.webhook-secret 常量时间比较
 *   3. 兼容模式:配置未设置时跳过校验(WARN 日志提示),平滑迁移
 *
 * alertmanager.yml 配置示例:
 *   receivers:
 *     - name: 'ops-webhook'
 *       webhook_configs:
 *         - url: 'http://host.docker.internal:8080/api/alerts/webhook'
 *           send_resolved: true
 *           http_config:
 *             authorization:
 *               type: Bearer
 *               credentials: 'your-secret-here'
 */
@Slf4j
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertWebhookController {

    private final AlertCenterService alertCenter;
    private final AuditLogService auditLog;
    private final OpsMonitorProperties properties;

    /** HTTP Authorization header 前缀(AlertManager 标准 Bearer 方案) */
    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * AlertManager → OpsMonitor 告警 webhook 入口
     *
     * 注意:此端点位于 SecurityInterceptor.PUBLIC_PATHS(不走 Token 认证)
     * 认证改用 Authorization: Bearer <secret> header(由本方法内部校验)
     */
    @PostMapping("/webhook")
    public ApiResponse<Map<String, Object>> receiveWebhook(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {

        // ========== 1. 认证校验 ==========
        String configuredSecret = properties.getSecurity() == null
                ? null : properties.getSecurity().getWebhookSecret();
        String authHeader = request.getHeader(AUTH_HEADER);
        String receivedSecret = null;
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            receivedSecret = authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        if (configuredSecret == null || configuredSecret.isBlank()) {
            // v2.26-sec: 未配置 secret 时，仅允许 Docker 网络来源
            // v2.33 SEC-1 修复: 原 startsWith("172.") 会误放 172.0-15 / 172.32-255 公网地址
            //   （Docker 默认网段是 172.16.0.0/12，即 172.16-172.31）
            String clientIp = getClientIp(request);
            boolean isDockerNetwork = clientIp != null && (
                    clientIp.startsWith("127.") ||
                    isDockerBridgeSubnet(clientIp) ||
                    "0:0:0:0:0:0:0:1".equals(clientIp) ||
                    "::1".equals(clientIp));
            if (!isDockerNetwork) {
                log.warn("[AlertWebhook] 拒绝：未配置 webhook-secret 且来源非 Docker 网络, from={}", clientIp);
                return ApiResponse.error(403, "webhook 未配置认证且来源不可信");
            }
            log.warn("[AlertWebhook] ⚠️ 未配置 webhook-secret, 仅允许 Docker 网络来源({})", clientIp);
        } else {
            if (receivedSecret == null || receivedSecret.isBlank()) {
                auditLog.logFail("alertmanager", "WEBHOOK_UNAUTH", getClientIp(request),
                        "缺少 Authorization: Bearer header");
                log.warn("[AlertWebhook] 拒绝:缺少 Authorization Bearer header, from={}", getClientIp(request));
                return ApiResponse.error(401, "缺少 webhook 认证 header");
            }
            if (!constantTimeEquals(configuredSecret, receivedSecret)) {
                auditLog.logFail("alertmanager", "WEBHOOK_UNAUTH", getClientIp(request),
                        "Bearer 密钥不匹配");
                log.warn("[AlertWebhook] 拒绝:Bearer 密钥不匹配, from={}", getClientIp(request));
                return ApiResponse.error(401, "webhook 密钥不匹配");
            }
        }

        // ========== 2. 解析 AlertManager payload ==========
        //
        // 标准 AlertManager webhook 格式:
        // {
        //   "status": "firing" | "resolved",
        //   "alerts": [
        //     { "status":..., "labels":{...}, "annotations":{...}, "startsAt":..., "endsAt":..., "fingerprint":... },
        //     ...
        //   ]
        // }
        int processed = 0;
        try {
            Object alertsObj = payload.get("alerts");
            if (alertsObj instanceof List<?>) {
                for (Object a : (List<?>) alertsObj) {
                    if (!(a instanceof Map<?, ?>)) continue;
                    Map<?, ?> amAlert = (Map<?, ?>) a;
                    AlertCenterService.Alert alert = convertToInternal(amAlert);
                    if (alert != null) {
                        alertCenter.receiveAlert(alert);
                        processed++;
                    }
                }
            }
        } catch (Exception e) {
            log.error("[AlertWebhook] 解析 payload 失败: {}", e.getMessage(), e);
            return ApiResponse.error(400, "payload 格式错误: " + e.getMessage());
        }

        log.info("[AlertWebhook] 成功处理 {} 条告警, from={}", processed, getClientIp(request));
        Map<String, Object> result = new HashMap<>();
        result.put("processed", processed);
        result.put("status", "ok");
        return ApiResponse.ok(result);
    }

    // ==================== 辅助方法 ====================

    /**
     * 常量时间字符串比较,防定时攻击
     * 即使字符串不等长,也始终比较所有字符,不提前返回
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // JDK 内置常量时间比较
        return java.security.MessageDigest.isEqual(aBytes, bBytes);
    }

    /**
     * 将 AlertManager 格式告警转换为内部 Alert 对象
     */
    @SuppressWarnings("unchecked")
    private AlertCenterService.Alert convertToInternal(Map<?, ?> amAlert) {
        try {
            Map<String, String> labels = castToStringMap(amAlert.get("labels"));
            Map<String, String> annotations = castToStringMap(amAlert.get("annotations"));

            // 不能用 amAlert.getOrDefault(K, V) — Map<?,?> 的 V 是 capture<?>,不接受 String
            Object statusRaw = amAlert.get("status");
            String status = statusRaw != null ? String.valueOf(statusRaw) : "firing";

            AlertCenterService.Alert alert = new AlertCenterService.Alert();
            alert.setStatus(status);
            alert.setAlertName(labels.getOrDefault("alertname", "Unknown"));
            alert.setSeverity(labels.getOrDefault("severity", "warning"));
            alert.setServerName(labels.get("server_name"));
            alert.setExporterType(labels.get("exporter_type"));
            alert.setTenant(labels.get("tenant"));
            alert.setSummary(annotations.get("summary"));
            alert.setDescription(annotations.get("description"));
            alert.setLabels(labels);
            return alert;
        } catch (Exception e) {
            log.warn("[AlertWebhook] 转换告警失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> castToStringMap(Object raw) {
        Map<String, String> out = new HashMap<>();
        if (raw instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey().toString(), e.getValue().toString());
                }
            }
        }
        return out;
    }

    private String getClientIp(HttpServletRequest request) {
        // 简化版,不做 X-Forwarded-For 解析(webhook 是容器内调用,直接用 remoteAddr)
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    /**
     * v2.33 SEC-1: 精确判断 Docker bridge 网段 172.16.0.0/12（172.16.x.x - 172.31.x.x）
     * 避免 startsWith("172.") 误放 172.0-15 / 172.32-255 等公网地址
     */
    private boolean isDockerBridgeSubnet(String ip) {
        if (ip == null || !ip.startsWith("172.")) return false;
        int dot = ip.indexOf('.', 4);
        if (dot < 0) return false;
        try {
            int second = Integer.parseInt(ip.substring(4, dot));
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
