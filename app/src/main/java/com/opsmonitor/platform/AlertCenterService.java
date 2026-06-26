package com.opsmonitor.platform;

import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * 告警中心 (10D-6 + 11A 通知分发)
 *
 * 11A 新增：receiveAlert 时触发 NotificationDispatcher 异步分发
 *
 * 替代 AlertManager 的黑盒模式，在 OpsMonitor 内管理告警全生命周期。
 *
 * 能力：
 * - 当前活跃告警列表
 * - 告警历史（最近 500 条）
 * - ACK（确认告警，标记已知）
 * - Silence（静默规则，按 alertname/server_name/exporter_type）
 * - 统计（按 severity/alertname/tenant 分组）
 *
 * 告警生命周期：
 *   FIRING → (ACK) → ACKNOWLEDGED → (resolve) → RESOLVED
 *   FIRING → (silence match) → SILENCED
 */
@Slf4j
@Service
public class AlertCenterService {

    /** 11A: 通知分发（setter注入，避免循环依赖）*/
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.opsmonitor.platform.NotificationDispatcher notificationDispatcher;

    /** v2.5 Sentinel: 告警触发后自动诊断（setter注入，避免循环依赖）*/
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.opsmonitor.sentinel.DiagnosisEngine diagnosisEngine;

    /** v2.5 Sentinel: Incident 管理（setter注入）*/
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.opsmonitor.sentinel.IncidentService incidentService;

    /** v2.5: 异步诊断线程池（daemon，不阻止 JVM 关闭）*/
    private static final java.util.concurrent.ExecutorService DIAGNOSE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "sentinel-diagnose");
                t.setDaemon(true);
                return t;
            });

    /** 活跃告警: alertId → Alert */
    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();

    /** 告警历史 */
    private final ConcurrentLinkedDeque<Alert> alertHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 500;

    /** 静默规则 */
    private final Map<String, SilenceRule> silenceRules = new ConcurrentHashMap<>();

    /**
     * 接收 AlertManager webhook 告警
     */
    public void receiveAlert(Alert alert) {
        if (alert.getAlertId() == null) {
            alert.setAlertId(generateAlertId(alert));
        }
        alert.setReceivedAt(System.currentTimeMillis());

        // 检查静默
        if (matchesSilence(alert)) {
            alert.setState("SILENCED");
            log.debug("[AlertCenter] 告警已静默: {}", alert.getAlertName());
            addToHistory(alert);
            return;
        }

        if ("resolved".equals(alert.getStatus())) {
            // 解决告警
            Alert existing = activeAlerts.remove(alert.getAlertId());
            if (existing != null) {
                existing.setState("RESOLVED");
                existing.setResolvedAt(System.currentTimeMillis());
                addToHistory(existing);
                // 11A: 触发恢复通知
                if (notificationDispatcher != null) {
                    notificationDispatcher.dispatch(existing);
                }
            }
            alert.setState("RESOLVED");
            addToHistory(alert);
            log.info("[AlertCenter] 告警已解决: {} ({})", alert.getAlertName(), alert.getAlertId());
        } else {
            // 新告警或更新
            alert.setState("FIRING");
            activeAlerts.put(alert.getAlertId(), alert);
            addToHistory(alert);
            log.warn("[AlertCenter] 告警触发: {} severity={}", alert.getAlertName(), alert.getSeverity());
            // 11A: 触发外部通知
            if (notificationDispatcher != null) {
                notificationDispatcher.dispatch(alert);
            }
            // v2.5 Sentinel: FIRING 告警异步触发诊断（非阻塞，不影响告警接收流程）
            triggerDiagnose(alert);
        }
    }

    /**
     * v2.5: 告警触发后异步诊断
     *
     * 设计：
     *  - 仅对 FIRING 告警触发，RESOLVED/SILENCED 跳过
     *  - 从告警 labels 中提取 server_id（File SD 写入的标签）
     *  - 置信度 ≥ 60% 时 IncidentService 自动创建 Incident
     *  - 使用独立线程池，不阻塞 receiveAlert 主流程
     */
    private void triggerDiagnose(Alert alert) {
        if (diagnosisEngine == null || incidentService == null) return;
        // 从告警 labels 提取 server_id
        String serverId = null;
        String serverName = null;
        if (alert.getLabels() != null) {
            serverId   = alert.getLabels().get("server_id");
            serverName = alert.getLabels().get("server_name");
        }
        if (serverId == null || serverId.isBlank()) {
            log.debug("[AlertCenter→Sentinel] 告警缺少 server_id label，跳过诊断: {}", alert.getAlertName());
            return;
        }
        final String sid  = serverId;
        final String sname = serverName != null ? serverName : serverId;
        DIAGNOSE_POOL.submit(() -> {
            try {
                log.info("[AlertCenter→Sentinel] 告警 {} 触发自动诊断: serverId={}", alert.getAlertName(), sid);
                java.util.List<com.opsmonitor.sentinel.DiagnosisEngine.DiagnosisReport> reports =
                        diagnosisEngine.diagnose(sid, sname);
                for (com.opsmonitor.sentinel.DiagnosisEngine.DiagnosisReport r : reports) {
                    if (r.getConfidence() >= 60.0) {
                        java.util.List<com.opsmonitor.sentinel.RunbookStep> steps = buildLogSteps(r);
                        // v2.12: 调用带 indicatorSnapshot 的重载，传入诊断快照供事后分析
                        incidentService.open(sid, sname, r.getFaultId(), r.getFaultName(),
                                r.getSeverity(), r.getImpactScore(), r.getConfidence(),
                                r.getRootCause(), steps,
                                r.getIndicatorSnapshot(), null);
                        log.info("[AlertCenter→Sentinel] 自动创建 Incident: {} [{}] confidence={}%",
                                r.getFaultName(), r.getSeverity(), String.format("%.0f", r.getConfidence()));
                    }
                }
            } catch (IllegalStateException e) {
                // P1-8 fix: Incident 上限或去重拒绝，需明确记录 ERROR 以便运维知晓
                log.error("[AlertCenter→Sentinel] Incident 创建被拒绝: {}", e.getMessage());
            } catch (Exception e) {
                log.warn("[AlertCenter→Sentinel] 自动诊断异常（不影响告警流程）: {}", e.getMessage());
            }
        });
    }

    /** 将诊断报告转为 LOG 类型 RunbookStep（不需要人工操作，仅记录信息）*/
    private java.util.List<com.opsmonitor.sentinel.RunbookStep> buildLogSteps(
            com.opsmonitor.sentinel.DiagnosisEngine.DiagnosisReport r) {
        java.util.List<com.opsmonitor.sentinel.RunbookStep> steps = new java.util.ArrayList<>();
        steps.add(com.opsmonitor.sentinel.RunbookStep.builder()
                .name("诊断摘要").type("LOG")
                .command("告警触发自动诊断 | 故障: " + r.getFaultName()
                        + " | 置信度: " + String.format("%.0f", r.getConfidence()) + "%"
                        + " | 根因: " + r.getRootCause())
                .failFast(false).build());
        if (r.getRunbook() != null) {
            for (String step : r.getRunbook()) {
                steps.add(com.opsmonitor.sentinel.RunbookStep.builder()
                        .name(step.length() > 40 ? step.substring(0, 40) : step)
                        .type("LOG").command(step).failFast(false).build());
            }
        }
        return steps;
    }

    /**
     * ACK 确认告警
     */
    public boolean ackAlert(String alertId, String acknowledgedBy) {
        Alert alert = activeAlerts.get(alertId);
        if (alert == null) return false;
        alert.setState("ACKNOWLEDGED");
        alert.setAcknowledgedBy(acknowledgedBy);
        alert.setAcknowledgedAt(System.currentTimeMillis());
        log.info("[AlertCenter] 告警已确认: {} by {}", alertId, acknowledgedBy);
        return true;
    }

    /**
     * 创建静默规则
     */
    public SilenceRule createSilence(SilenceRule rule) {
        if (rule.getId() == null) {
            rule.setId("silence-" + System.currentTimeMillis());
        }
        rule.setCreatedAt(System.currentTimeMillis());
        silenceRules.put(rule.getId(), rule);
        log.info("[AlertCenter] 创建静默: {} (匹配: alertname={}, server={})",
                rule.getId(), rule.getMatchAlertName(), rule.getMatchServerName());
        return rule;
    }

    /**
     * 删除静默规则
     */
    public boolean deleteSilence(String silenceId) {
        return silenceRules.remove(silenceId) != null;
    }

    // ==================== 查询 ====================

    public List<Alert> getActiveAlerts() {
        return new ArrayList<>(activeAlerts.values());
    }

    public List<Alert> getActiveAlertsByTenant(String tenant) {
        return activeAlerts.values().stream()
                .filter(a -> tenant.equals(a.getTenant()))
                .collect(Collectors.toList());
    }

    public List<Alert> getAlertHistory(int limit) {
        return alertHistory.stream().limit(limit).collect(Collectors.toList());
    }

    public List<SilenceRule> listSilences() {
        return new ArrayList<>(silenceRules.values());
    }

    public Map<String, Long> statsBySeverity() {
        return activeAlerts.values().stream()
                .collect(Collectors.groupingBy(Alert::getSeverity, Collectors.counting()));
    }

    public Map<String, Long> statsByAlertName() {
        return activeAlerts.values().stream()
                .collect(Collectors.groupingBy(Alert::getAlertName, Collectors.counting()));
    }

    // ==================== 内部方法 ====================

    private boolean matchesSilence(Alert alert) {
        long now = System.currentTimeMillis();
        return silenceRules.values().stream().anyMatch(rule -> {
            if (rule.getExpiresAt() > 0 && now > rule.getExpiresAt()) return false;
            if (rule.getMatchAlertName() != null && !rule.getMatchAlertName().equals(alert.getAlertName())) return false;
            if (rule.getMatchServerName() != null && !rule.getMatchServerName().equals(alert.getServerName())) return false;
            if (rule.getMatchExporterType() != null && !rule.getMatchExporterType().equals(alert.getExporterType())) return false;
            return true;
        });
    }

    private void addToHistory(Alert alert) {
        alertHistory.addFirst(alert);
        while (alertHistory.size() > MAX_HISTORY) {
            alertHistory.removeLast();
        }
    }

    private String generateAlertId(Alert alert) {
        return alert.getAlertName() + "-" + Objects.hash(alert.getServerName(), alert.getExporterType());
    }

    // ==================== 数据模型 ====================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Alert {
        private String alertId;
        private String alertName;
        private String severity; // warning / critical
        private String status; // firing / resolved
        private String state; // FIRING / ACKNOWLEDGED / SILENCED / RESOLVED
        private String serverName;
        private String exporterType;
        private String tenant;
        private String summary;
        private String description;
        private String acknowledgedBy;
        private long receivedAt;
        private long acknowledgedAt;
        private long resolvedAt;
        private Map<String, String> labels;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SilenceRule {
        private String id;
        private String matchAlertName;
        private String matchServerName;
        private String matchExporterType;
        private String reason;
        private String createdBy;
        private long createdAt;
        private long expiresAt; // 0=永不过期
    }
    /**
     * v2.10 P1-07 修复:注册线程池关闭钩子,避免 Spring DevTools 热重启/多次 @SpringBootTest 累积 → OOM
     */
    @PreDestroy
    public void shutdownThreadPool_v210() {
        try {
            if (DIAGNOSE_POOL != null && !DIAGNOSE_POOL.isShutdown()) {
                DIAGNOSE_POOL.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
}