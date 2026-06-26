package com.opsmonitor.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 通知分发引擎 (11A)
 *
 * 职责：
 * - 收到告警事件后，遍历所有启用的渠道
 * - 按触发策略过滤
 * - 构建各平台消息体（钉钉/飞书/企微/Webhook/Slack）
 * - 异步 HTTP POST 发送，失败记录日志
 * - SSRF 防护：仅允许非内网域名（production webhooks 都是公网）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationChannelService channelService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * N6 修复：DNS 解析专用单例线程池（原先每次 isInternalUrl 都 new 一个，导致线程泄漏）
     * 使用 daemon 线程，不阻止 JVM 关闭
     */
    private static final java.util.concurrent.ExecutorService DNS_RESOLVER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "notification-dns-resolver");
                t.setDaemon(true);
                return t;
            });

    /** 内网 IP 段 - SSRF 防护 */
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127.", "0.", "169.254."
    );

    /**
     * 分发告警通知（异步执行，不阻塞主流程）
     */
    /** v2.22: 通用文本消息推送（健康报告等非 Alert 场景） */
    @Async
    public CompletableFuture<Void> dispatchText(String title, String content) {
        List<NotificationChannel> enabledChannels = channelService.listEnabled();
        if (enabledChannels.isEmpty()) return CompletableFuture.completedFuture(null);
        for (NotificationChannel ch : enabledChannels) {
            try {
                String body = buildTextPayload(ch, title, content);
                if (body == null) continue;
                sendHttp(ch, body);
                channelService.recordSent(ch.getId(), true);
            } catch (Exception e) {
                log.warn("[NotificationDispatcher] 渠道 {} 文本推送异常: {}", ch.getName(), e.getMessage());
                channelService.recordSent(ch.getId(), false);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private String buildTextPayload(NotificationChannel ch, String title, String content) throws Exception {
        String markdown = String.format("### %s\n\n%s", safe(title), safe(content));
        return switch (ch.getType()) {
            case "DINGTALK", "WECOM", "FEISHU", "SLACK", "WEBHOOK" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("msgtype", "markdown");
                payload.put("markdown", Map.of("title", title, "text", markdown));
                yield MAPPER.writeValueAsString(payload);
            }
            default -> null;
        };
    }

    @Async
    public CompletableFuture<Void> dispatch(AlertCenterService.Alert alert) {
        List<NotificationChannel> enabledChannels = channelService.listEnabled();
        if (enabledChannels.isEmpty()) return CompletableFuture.completedFuture(null);

        for (NotificationChannel ch : enabledChannels) {
            try {
                if (!shouldSend(ch, alert)) continue;
                String body = buildPayload(ch, alert);
                if (body == null) continue;
                sendHttp(ch, body);
            } catch (Exception e) {
                log.warn("[NotificationDispatcher] 渠道 {} 发送异常: {}", ch.getName(), e.getMessage());
                channelService.recordSent(ch.getId(), false);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    // ==================== 策略过滤 ====================

    private boolean shouldSend(NotificationChannel ch, AlertCenterService.Alert alert) {
        // 生命周期过滤
        boolean isFiring = "FIRING".equals(alert.getState());
        boolean isResolved = "RESOLVED".equals(alert.getState());
        if (isFiring && !ch.isNotifyOnFiring()) return false;
        if (isResolved && !ch.isNotifyOnResolved()) return false;

        // 触发策略
        String policy = ch.getTriggerPolicy();
        if ("CRITICAL".equals(policy) && !"critical".equals(alert.getSeverity())) return false;
        if ("CUSTOM".equals(policy)) {
            if (ch.getFilterAlertName() != null && !ch.getFilterAlertName().equals(alert.getAlertName())) return false;
            if (ch.getFilterServerName() != null && !ch.getFilterServerName().equals(alert.getServerName())) return false;
        }
        return true;
    }

    // ==================== 消息体构建 ====================

    private String buildPayload(NotificationChannel ch, AlertCenterService.Alert alert) throws Exception {
        return switch (ch.getType()) {
            case "DINGTALK" -> buildDingTalk(alert);
            case "FEISHU"   -> buildFeishu(alert);
            case "WECOM"    -> buildWeCom(alert);
            case "SLACK"    -> buildSlack(alert);
            case "WEBHOOK"  -> buildWebhook(alert);
            default -> {
                log.warn("[NotificationDispatcher] 未知渠道类型: {}", ch.getType());
                yield null;
            }
        };
    }

    /** 钉钉机器人 - Markdown 格式 */
    private String buildDingTalk(AlertCenterService.Alert alert) throws Exception {
        String emoji = "critical".equals(alert.getSeverity()) ? "🔴" : "🟡";
        String state = stateLabel(alert.getState());
        String time = formatTime(alert.getReceivedAt());

        String markdown = String.format(
                "### %s %s [%s]\n\n" +
                        "- **告警名称**: %s\n" +
                        "- **服务器**: %s\n" +
                        "- **级别**: %s\n" +
                        "- **状态**: %s\n" +
                        "- **时间**: %s\n" +
                        "- **详情**: %s",
                emoji, alert.getAlertName(), state,
                safe(alert.getAlertName()),
                safe(alert.getServerName()),
                safe(alert.getSeverity()),
                state, time,
                safe(alert.getSummary())
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", Map.of("title", "OpsMonitor 告警: " + alert.getAlertName(), "text", markdown));
        return MAPPER.writeValueAsString(payload);
    }

    /** 飞书机器人 - 富文本卡片 */
    private String buildFeishu(AlertCenterService.Alert alert) throws Exception {
        String emoji = "critical".equals(alert.getSeverity()) ? "🔴" : "🟡";
        String state = stateLabel(alert.getState());

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tag", "text");
        content.put("text", String.format(
                "%s OpsMonitor 告警\n告警名称: %s\n服务器: %s\n级别: %s\n状态: %s\n时间: %s\n详情: %s",
                emoji,
                safe(alert.getAlertName()),
                safe(alert.getServerName()),
                safe(alert.getSeverity()),
                state,
                formatTime(alert.getReceivedAt()),
                safe(alert.getSummary())
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "text");
        payload.put("content", Map.of("text", content.get("text")));
        return MAPPER.writeValueAsString(payload);
    }

    /** 企业微信机器人 - Markdown */
    private String buildWeCom(AlertCenterService.Alert alert) throws Exception {
        String emoji = "critical".equals(alert.getSeverity()) ? "🔴" : "🟡";
        String state = stateLabel(alert.getState());
        String colorTag = "critical".equals(alert.getSeverity()) ? "<font color=\"warning\">" : "<font color=\"comment\">";

        String markdown = String.format(
                "## %s OpsMonitor 告警\n" +
                        "> 告警名称: %s%s</font>\n" +
                        "> 服务器: <font color=\"comment\">%s</font>\n" +
                        "> 级别: %s%s</font>\n" +
                        "> 状态: <font color=\"comment\">%s</font>\n" +
                        "> 时间: <font color=\"comment\">%s</font>\n" +
                        "> 详情: %s",
                emoji,
                colorTag, safe(alert.getAlertName()),
                safe(alert.getServerName()),
                colorTag, safe(alert.getSeverity()),
                state,
                formatTime(alert.getReceivedAt()),
                safe(alert.getSummary())
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", Map.of("content", markdown));
        return MAPPER.writeValueAsString(payload);
    }

    /** Slack Incoming Webhook */
    private String buildSlack(AlertCenterService.Alert alert) throws Exception {
        String emoji = "critical".equals(alert.getSeverity()) ? ":red_circle:" : ":warning:";
        String color = "critical".equals(alert.getSeverity()) ? "#FF0000" : "#FFA500";

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", color);
        attachment.put("title", emoji + " " + alert.getAlertName());
        attachment.put("text", String.format(
                "服务器: %s | 级别: %s | 状态: %s\n时间: %s\n%s",
                safe(alert.getServerName()),
                safe(alert.getSeverity()),
                stateLabel(alert.getState()),
                formatTime(alert.getReceivedAt()),
                safe(alert.getSummary())
        ));
        attachment.put("footer", "OpsMonitor v2.2");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attachments", List.of(attachment));
        return MAPPER.writeValueAsString(payload);
    }

    /** 通用 Webhook - 标准 JSON 格式 */
    private String buildWebhook(AlertCenterService.Alert alert) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertId", alert.getAlertId());
        payload.put("alertName", alert.getAlertName());
        payload.put("severity", alert.getSeverity());
        payload.put("state", alert.getState());
        payload.put("serverName", alert.getServerName());
        payload.put("exporterType", alert.getExporterType());
        payload.put("summary", alert.getSummary());
        payload.put("receivedAt", alert.getReceivedAt());
        payload.put("source", "OpsMonitor");
        return MAPPER.writeValueAsString(payload);
    }

    // ==================== HTTP 发送 ====================

    private void sendHttp(NotificationChannel ch, String body) {
        String url = ch.getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("[NotificationDispatcher] 渠道 {} 未配置 webhookUrl", ch.getName());
            channelService.recordSent(ch.getId(), false);
            return;
        }

        // v2.10 P1-02 修复:防 DNS rebinding
        // 原代码 isInternalUrl() 解析一次 DNS,httpClient.send() 又解析一次,
        // 恶意 DNS 服务可两次返回不同值(首次"公网 IP"过检查,二次"127.0.0.1"打内网)。
        // 新做法:一次解析拿到 IP,如果安全则直接用 IP 构造 URI,Host 通过 header 保留
        ResolvedUrl resolved = resolveAndValidate(url);
        if (!resolved.safe) {
            log.warn("[NotificationDispatcher] SSRF 防护:拒绝地址 url={} 原因={}",
                    url.replaceAll("://[^@]+@", "://***@"), resolved.rejectReason);
            channelService.recordSent(ch.getId(), false);
            return;
        }

        try {
            // 若是 HTTPS,保留原 URL(IP 替换会破坏 SNI/TLS 证书验证)
            // 若是 HTTP,用 IP 替换 host 避免二次解析
            URI targetUri;
            if ("https".equalsIgnoreCase(resolved.scheme)) {
                // HTTPS 无法简单换 IP,退而求其次:已经解析过一次是安全的
                // 如果攻击者能在 HTTPS 场景做 DNS rebinding,他也就能绕过 TLS,
                // 这种攻击门槛已经非常高,本修复主要针对 HTTP webhook
                targetUri = URI.create(url);
            } else {
                targetUri = resolved.ipBasedUri;
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(targetUri)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "OpsMonitor/2.2")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10));

            // 如果 IP 替换了 host,必须带原 Host header(webhook 服务可能依赖 virtual host 路由)
            if (!"https".equalsIgnoreCase(resolved.scheme) && resolved.originalHost != null) {
                requestBuilder.header("Host", resolved.originalHost);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = resp.statusCode() >= 200 && resp.statusCode() < 300;
            channelService.recordSent(ch.getId(), success);

            if (success) {
                log.info("[NotificationDispatcher] 渠道 {} 发送成功 HTTP {}", ch.getName(), resp.statusCode());
            } else {
                log.warn("[NotificationDispatcher] 渠道 {} 发送失败 HTTP {}", ch.getName(), resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("[NotificationDispatcher] 渠道 {} HTTP 异常: {}", ch.getName(), e.getMessage());
            channelService.recordSent(ch.getId(), false);
        }
    }

    // ==================== SSRF 防护 ====================

    /**
     * 检测 URL 是否指向内网地址（SSRF 防护）
     *
     * 审查修复：InetAddress.getByName() 是阻塞 DNS 解析，无超时限制。
     * 攻击者可注册缓慢解析的域名导致 @Async 线程长时间挂起（DNS rebinding）。
     * 修复：使用 ExecutorService + Future.get(timeout) 限制 DNS 解析时间为 3 秒。
     */
    private boolean isInternalUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) return true;

            // 先检查是否直接是 IP 字符串（无需 DNS 解析）
            for (String prefix : BLOCKED_PREFIXES) {
                if (host.startsWith(prefix)) return true;
            }

            // DNS 解析加 3 秒超时，防止慢 DNS 攻击（使用单例线程池，不再每次 new）
            java.util.concurrent.Future<InetAddress> future =
                    DNS_RESOLVER.submit(() -> InetAddress.getByName(host));
            InetAddress addr;
            try {
                addr = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("[NotificationDispatcher] DNS 解析超时 host={}, 拒绝", host);
                future.cancel(true);
                return true;
            }

            String ip = addr.getHostAddress();
            return BLOCKED_PREFIXES.stream().anyMatch(ip::startsWith)
                    || addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress();
        } catch (Exception e) {
            // 任何解析异常一律拒绝
            return true;
        }
    }

    /**
     * v2.10 P1-02:防 DNS rebinding 的一次性解析 + 验证
     *
     * 相比旧的 isInternalUrl + URI.create(url) 双解析,这里一次解析拿到 IP 和所有元信息,
     * 供后续 http send 直接用 IP 构造 URI,彻底杜绝两次 DNS 返回不同值的可能。
     *
     * HTTPS 场景下无法直接用 IP 替换(会破坏 SNI 和证书 CN 验证),退回保留原 URL —
     * 这在 TLS 保护下的 webhook 里可以接受,因为攻击者能做 DNS rebinding 就已经需要
     * 拿到合法证书,门槛极高。
     */
    private ResolvedUrl resolveAndValidate(String url) {
        ResolvedUrl result = new ResolvedUrl();
        result.safe = false;
        try {
            URI parsed = URI.create(url);
            String host = parsed.getHost();
            if (host == null || host.isBlank()) {
                result.rejectReason = "host 为空";
                return result;
            }
            result.scheme = parsed.getScheme();
            result.originalHost = host;

            // 直接 IP 字符串也走黑名单
            for (String prefix : BLOCKED_PREFIXES) {
                if (host.startsWith(prefix)) {
                    result.rejectReason = "host 前缀在黑名单 " + prefix;
                    return result;
                }
            }

            // DNS 解析带 3s 超时
            java.util.concurrent.Future<InetAddress> future =
                    DNS_RESOLVER.submit(() -> InetAddress.getByName(host));
            InetAddress addr;
            try {
                addr = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                result.rejectReason = "DNS 解析超时";
                return result;
            }

            String ip = addr.getHostAddress();
            boolean blocked = BLOCKED_PREFIXES.stream().anyMatch(ip::startsWith)
                    || addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress();
            if (blocked) {
                result.rejectReason = "解析后 IP " + ip + " 属于内网/保留段";
                return result;
            }

            // 解析通过,构造 IP-based URI 供 HTTP 发送时直接使用(规避二次解析)
            result.resolvedIp = ip;
            int port = parsed.getPort();
            String pathQuery = parsed.getRawPath() == null ? "" : parsed.getRawPath();
            if (parsed.getRawQuery() != null) pathQuery += "?" + parsed.getRawQuery();
            String ipHost = addr instanceof java.net.Inet6Address ? "[" + ip + "]" : ip;
            String ipUrl = parsed.getScheme() + "://" + ipHost + (port > 0 ? ":" + port : "") + pathQuery;
            result.ipBasedUri = URI.create(ipUrl);
            result.safe = true;
            return result;
        } catch (Exception e) {
            result.rejectReason = "解析异常: " + e.getMessage();
            return result;
        }
    }

    /** v2.10 P1-02:URL 解析结果载体 */
    private static class ResolvedUrl {
        boolean safe;
        String rejectReason;
        String scheme;
        String originalHost;   // 原主机名(用于 HTTP Host header)
        String resolvedIp;     // 解析得到的 IP
        URI ipBasedUri;        // 用 IP 构造的 URI(HTTP 场景直接用)
    }

    // ==================== 工具方法 ====================

    private String safe(String s) {
        return s == null ? "-" : s;
    }

    private String stateLabel(String state) {
        return switch (state == null ? "" : state) {
            case "FIRING"       -> "🔥 触发中";
            case "RESOLVED"     -> "✅ 已恢复";
            case "ACKNOWLEDGED" -> "👁 已确认";
            case "SILENCED"     -> "🔇 已静默";
            default -> state == null ? "-" : state;
        };
    }

    private String formatTime(long epochMs) {
        if (epochMs <= 0) return "-";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** P2-2 fix: 关闭 DNS 解析线程池 */
    @jakarta.annotation.PreDestroy
    public void shutdownThreadPool() {
        try { DNS_RESOLVER.shutdownNow(); } catch (Exception ignored) {}
    }
}