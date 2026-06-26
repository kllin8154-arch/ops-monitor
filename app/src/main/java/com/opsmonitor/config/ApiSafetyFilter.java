package com.opsmonitor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * API 安全过滤器 (第二轮审计修复)
 *
 * P0-5: PromQL 查询安全限制
 * P0-6: SSRF 防护（禁止探测危险地址）
 * P1-8: Host 格式校验
 * P1-9: Exporter type 白名单
 * P1-10: 字符串 XSS 清理
 * P1-11: 日志行数限制
 * P1-12: Agent 注册数量上限
 * P2-9: 原子写入工具
 * P2-11: 登录暴力破解防护
 */
@Slf4j
@Component
public class ApiSafetyFilter {

    // ==================== P0-5: PromQL 安全限制 ====================

    private static final int MAX_PROMQL_LENGTH = 500;
    private static final long MAX_QUERY_RANGE_SECONDS = 7 * 24 * 3600; // 7 天
    private static final int MIN_STEP_SECONDS = 15;

    public void validatePromQL(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询表达式不能为空");
        }
        if (query.length() > MAX_PROMQL_LENGTH) {
            throw new IllegalArgumentException("查询表达式过长（最大 " + MAX_PROMQL_LENGTH + " 字符）");
        }
        // v2.10 P2-01 修复:禁止危险函数和爆炸性查询
        validatePromQLSafety(query);
    }

    /** v2.10 P2-01:禁止的 VictoriaMetrics/Prometheus 危险 API 关键字 */
    private static final java.util.regex.Pattern DANGEROUS_PROMQL = java.util.regex.Pattern.compile(
            // 管理/删除类 API(VictoriaMetrics 特有)
            "\\b(delete_series|drop_series|import_csv|flush)\\s*\\("
                    + "|/api/v1/admin/tsdb/"
    );

    /** v2.10 P2-01:爆炸性 label 匹配(所有 metric)+ 长时间范围组合(易 OOM 查询) */
    private static final java.util.regex.Pattern EXPLOSIVE_MATCH = java.util.regex.Pattern.compile(
            // __name__ regex 匹配所有或几乎所有 metric
            "\\{\\s*__name__\\s*=~\\s*\"\\.[*+]\""
    );

    /** v2.10 P2-01:超长时间范围(>24h 的 range vector) */
    private static final java.util.regex.Pattern LONG_RANGE = java.util.regex.Pattern.compile(
            "\\[\\s*(\\d+)\\s*([dw])\\s*\\]"  // 形如 [30d] [4w]
    );

    private void validatePromQLSafety(String query) {
        // 危险函数直接拒
        if (DANGEROUS_PROMQL.matcher(query).find()) {
            throw new IllegalArgumentException("查询包含禁止的危险函数(delete_series / import_csv / admin/tsdb 等)");
        }
        // __name__=~".+" 这种全库扫描
        if (EXPLOSIVE_MATCH.matcher(query).find()) {
            throw new IllegalArgumentException("查询包含 __name__ 全匹配,风险过高,请使用具体 metric 名");
        }
        // [Nd] [Nw] 且 N 较大(>7d 或 >1w)
        java.util.regex.Matcher m = LONG_RANGE.matcher(query);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            int days = "w".equals(unit) ? n * 7 : n;
            if (days > 7) {
                throw new IllegalArgumentException("range vector 时间窗口过大(" + m.group(0).trim() + "),最多 7d");
            }
        }
    }

    public void validateQueryRange(long startEpoch, long endEpoch, int stepSeconds) {
        if (endEpoch - startEpoch > MAX_QUERY_RANGE_SECONDS) {
            throw new IllegalArgumentException("查询范围不能超过 7 天");
        }
        if (stepSeconds < MIN_STEP_SECONDS) {
            throw new IllegalArgumentException("step 不能小于 " + MIN_STEP_SECONDS + " 秒");
        }
    }

    // ==================== P0-6: SSRF 防护 ====================

    /** 禁止探测的 IP 段 */
    private static final Set<String> BLOCKED_IP_PREFIXES = Set.of(
            "169.254.", "0.0.0.", "255.255.255.",
            "::1", "fe80:", "fc00:", "fd00:"
    );

    /** 允许探测的端口范围（Exporter 端口） */
    private static final int MIN_PROBE_PORT = 1024;
    private static final int MAX_PROBE_PORT = 65535;

    public void validateProbeTarget(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("目标地址不能为空");
        }

        // 检查危险 IP
        for (String prefix : BLOCKED_IP_PREFIXES) {
            if (host.startsWith(prefix)) {
                throw new IllegalArgumentException("禁止探测该地址: " + host);
            }
        }

        // 禁止探测 metadata 服务 (AWS/GCP/Azure)
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() && port < 1024) {
                throw new IllegalArgumentException("禁止探测本地特权端口");
            }
            if (addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                throw new IllegalArgumentException("禁止探测该地址");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {}

        // 端口范围
        if (port < MIN_PROBE_PORT || port > MAX_PROBE_PORT) {
            throw new IllegalArgumentException("端口范围: " + MIN_PROBE_PORT + "-" + MAX_PROBE_PORT);
        }
    }

    // ==================== P1-8: Host 格式校验 ====================

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?$"
    );
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    );

    public void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("主机地址不能为空");
        }
        String trimmed = host.trim();
        if (!IP_PATTERN.matcher(trimmed).matches() && !HOST_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("主机地址格式无效: " + trimmed);
        }
    }

    // ==================== P1-9: Exporter type 白名单 ====================

    private static final Set<String> ALLOWED_EXPORTER_TYPES = Set.of(
            "redis", "mysql", "postgres", "oracle", "kingbase", "dm",
            "nginx", "geoserver", "jmx", "process", "node", "windows"
    );

    public void validateExporterType(String type) {
        if (type == null || !ALLOWED_EXPORTER_TYPES.contains(type.toLowerCase())) {
            throw new IllegalArgumentException("不支持的 Exporter 类型: " + type
                    + " (允许: " + ALLOWED_EXPORTER_TYPES + ")");
        }
    }

    // ==================== P1-10: XSS 清理 ====================

    // v2.26-sec: & 必须最先处理，防止新生成的 & 被二次转义
    public String sanitize(String input) {
        if (input == null) return null;
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public String sanitizeKeepAmp(String input) {
        if (input == null) return null;
        return input.replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ==================== P1-11: 日志行数限制 ====================

    private static final int MAX_LOG_TAIL = 1000;

    public int clampLogTail(int requested) {
        return Math.min(Math.max(requested, 1), MAX_LOG_TAIL);
    }

    // ==================== P1-12: Agent 注册上限 ====================

    private static final int MAX_AGENTS = 200;

    public void checkAgentLimit(int currentCount) {
        if (currentCount >= MAX_AGENTS) {
            throw new IllegalArgumentException("Agent 数量已达上限: " + MAX_AGENTS);
        }
    }

    // ==================== P2-11: 暴力破解防护 ====================

    /** username → {failCount, lastFailTime} */
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();
    private static final int  MAX_LOGIN_FAILURES = 5;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000L; // 5 分钟

    public void checkLoginThrottle(String username) {
        LoginAttempt attempt = loginAttempts.get(username);
        if (attempt == null) return;

        long now = System.currentTimeMillis();
        if (attempt.count.get() >= MAX_LOGIN_FAILURES
                && now - attempt.lastFailTime < LOCKOUT_DURATION_MS) {
            long remainSec = (LOCKOUT_DURATION_MS - (now - attempt.lastFailTime)) / 1000;
            throw new IllegalArgumentException("登录失败次数过多，请 " + remainSec + " 秒后重试");
        }

        // 过期自动清除
        if (now - attempt.lastFailTime > LOCKOUT_DURATION_MS) {
            loginAttempts.remove(username);
        }
    }

    public void recordLoginFailure(String username) {
        loginAttempts.compute(username, (k, v) -> {
            if (v == null) v = new LoginAttempt();
            v.count.incrementAndGet();
            v.lastFailTime = System.currentTimeMillis();
            return v;
        });
    }

    public void clearLoginFailure(String username) {
        loginAttempts.remove(username);
    }

    /**
     * 定期清理过期的登录尝试记录（每30分钟）
     * 修复：loginAttempts 与 TOKEN_BLACKLIST 同类问题，无清理会导致内存持续增长
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1_800_000L, initialDelay = 1_800_000L)
    public void cleanExpiredLoginAttempts() {
        long now = System.currentTimeMillis();
        int before = loginAttempts.size();
        // 清理锁定已过期的条目（过期时间为 lockout * 2，确保彻底过期）
        loginAttempts.entrySet().removeIf(e ->
                now - e.getValue().lastFailTime > LOCKOUT_DURATION_MS * 2);
        int removed = before - loginAttempts.size();
        if (removed > 0) {
            log.debug("[ApiSafetyFilter] 清理 {} 个过期登录尝试记录，剩余 {}",
                    removed, loginAttempts.size());
        }
    }

    private static class LoginAttempt {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long lastFailTime = 0;
    }

    // ==================== P2-9: 原子写入工具 ====================

    /**
     * 原子写入 JSON 文件（.tmp → rename）
     */
    public static void atomicWriteJson(java.nio.file.Path path, byte[] data) throws java.io.IOException {
        java.nio.file.Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        java.nio.file.Files.write(tmp, data);
        try {
            java.nio.file.Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Windows fallback
            java.nio.file.Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}