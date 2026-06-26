package com.opsmonitor.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuthService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 安全拦截器 (P0-2 + P2-4 + P2-7 + 审查修复 R3)
 *
 * 审查修复：
 * 1. TOKEN_BLACKLIST 改为 token→expiryTime Map，支持精确过期清理
 * 2. 新增 @Scheduled cleanExpiredTokens() 每小时自动清理，不再全清
 * 3. rateLimits 同步清理，防止 IP 条目内存持续增长
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final OpsMonitorProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * P2-7 + P2b修复: Token 黑名单 — token → 自然过期时间戳(ms)
     * 修复：存储 expiry 而非仅存 token，精确清理
     * P2b：黑名单持久化到文件，重启后已注销的 Token 不会复活
     */
    private static final ConcurrentHashMap<String, Long> TOKEN_BLACKLIST = new ConcurrentHashMap<>();

    @PostConstruct
    public void initBlacklist() {
        loadBlacklistFromFile();
    }

    // ===== P2b: 黑名单持久化 =====

    private Path getBlacklistFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "token-blacklist.json").normalize();
    }

    private void loadBlacklistFromFile() {
        Path f = getBlacklistFile();
        if (!Files.exists(f)) return;
        try {
            Map<String, Long> saved = new ObjectMapper().readValue(f.toFile(),
                    new TypeReference<Map<String, Long>>() {});
            long now = System.currentTimeMillis();
            // 只恢复未过期的条目
            saved.entrySet().stream()
                    .filter(e -> e.getValue() > now)
                    .forEach(e -> TOKEN_BLACKLIST.put(e.getKey(), e.getValue()));
            log.info("[Security] 已恢复 {} 条未过期 Token 黑名单", TOKEN_BLACKLIST.size());
        } catch (IOException e) {
            log.warn("[Security] 黑名单文件读取失败（将以空黑名单启动）: {}", e.getMessage());
        }
    }

    private static final ObjectMapper BL_MAPPER = new ObjectMapper();

    public void persistBlacklist() {
        Path f = getBlacklistFile();
        Path tmp = f.resolveSibling("token-blacklist.json.tmp");
        try {
            Files.createDirectories(f.getParent());
            // 只持久化未过期的条目
            long now = System.currentTimeMillis();
            Map<String, Long> active = new ConcurrentHashMap<>();
            TOKEN_BLACKLIST.entrySet().stream()
                    .filter(e -> e.getValue() > now)
                    .forEach(e -> active.put(e.getKey(), e.getValue()));
            BL_MAPPER.writeValue(tmp.toFile(), active);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[Security] 黑名单持久化失败: {}", e.getMessage());
        }
    }

    /** P2-4: IP 限流 */
    private final ConcurrentHashMap<String, RateLimit> rateLimits = new ConcurrentHashMap<>();
    private static final int  RATE_LIMIT     = 200;
    private static final long RATE_WINDOW_MS = 60_000L;

    /** Token 有效期（与 AuthService 保持一致：24h）*/
    private static final long TOKEN_TTL_MS = 24L * 60 * 60 * 1000;

    /**
     * 不需要认证的路径
     *
     * Phase3修复：新增只读状态查询端点为公开路径
     * 原因：前端 DOMContentLoaded 时立即调用 /api/system/status，
     * 此时 login-inject.js 可能尚未完成 Token 注入（异步），
     * 导致首屏 CPU/内存/磁盘 永远显示 N/A。
     * 这些接口只返回状态数据，无敏感操作，对外公开是合理的。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/health",
            "/api/ping",
            "/api/alerts/webhook",
            "/api/system/status",    // 系统总览（CPU/内存/磁盘）只读，首屏展示
            "/api/system/rules",     // v2.6: Recording Rules 验证，只读
            // v2.10 P0-02 修复：移除 Sentinel 端点,故障指纹/事件数据需认证后访问
            // 原放在 PUBLIC_PATHS 是为了前端首屏立即展示,但未登录用户不应看到故障诊断细节
            // 前端应在登录后再查询这些端点
            "/api/exporters/templates" // Exporter模板列表，只读
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();

        // 非 API 路径放行（静态资源、Thymeleaf 页面）
        if (!path.startsWith("/api/")) return true;

        // 限流检查
        String clientIp = getClientIp(request);
        if (!checkRateLimit(clientIp)) {
            writeError(response, 429, "请求过于频繁，请稍后重试");
            log.warn("[Security] IP {} 限流触发", clientIp);
            return false;
        }

        // 白名单
        for (String pub : PUBLIC_PATHS) {
            if (path.equals(pub) || path.startsWith(pub + "/")) return true;
        }

        // 认证未启用时放行
        if (!properties.getSecurity().isEnabled()) return true;

        // 提取 Token
        String token = extractToken(request);
        if (token == null) {
            writeError(response, 401, "未提供认证 Token，请先登录");
            return false;
        }

        // 黑名单检查（已注销）
        Long blacklistedExpiry = TOKEN_BLACKLIST.get(token);
        if (blacklistedExpiry != null) {
            if (System.currentTimeMillis() < blacklistedExpiry) {
                writeError(response, 401, "Token 已注销");
                return false;
            }
            // 条目本身已自然过期，顺手清理
            TOKEN_BLACKLIST.remove(token);
        }

        // 校验签名与过期
        User user = authService.validateToken(token);
        if (user == null) {
            writeError(response, 401, "Token 无效或已过期");
            return false;
        }

        request.setAttribute("currentUser", user);
        return true;
    }

    /**
     * P2-7: 注销 Token — 加入黑名单，记录自然过期时间
     */
    public static void revokeToken(String token) {
        if (token == null || token.isBlank()) return;
        TOKEN_BLACKLIST.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
    }

    /** P2b：注销并立即持久化（供非静态调用方使用） */
    public void revokeAndPersist(String token) {
        revokeToken(token);
        persistBlacklist();
    }

    /**
     * 定期精确清理（每小时）：移除已自然过期的黑名单条目 + 过期限流窗口
     * 修复：不再全清，确保未过期的已注销 Token 持续有效
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
    public void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        int beforeBl = TOKEN_BLACKLIST.size();
        TOKEN_BLACKLIST.entrySet().removeIf(e -> e.getValue() < now);
        int removedBl = beforeBl - TOKEN_BLACKLIST.size();
        // P2b：清理后持久化黑名单
        if (removedBl > 0) persistBlacklist();

        // 同步清理过期的限流窗口，防止 IP Map 无限增长
        rateLimits.entrySet().removeIf(e -> now - e.getValue().windowStart > RATE_WINDOW_MS * 2);

        if (removedBl > 0) {
            log.debug("[Security] 清理 {} 个过期黑名单 Token，当前剩余 {}",
                    removedBl, TOKEN_BLACKLIST.size());
        }
    }

    // ==================== 内部方法 ====================

    private String extractToken(HttpServletRequest request) {
        // P0-2 fix: 优先从 httpOnly Cookie 中读取 Token
        // Cookie 由 AuthController 登录成功时通过 Set-Cookie 设置，JS 无法读取（安全）
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("ops_token".equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        // 降级兼容：读取 Authorization Header（API 客户端、curl 等非浏览器场景）
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return null;
    }

    private boolean checkRateLimit(String ip) {
        long now = System.currentTimeMillis();
        RateLimit rl = rateLimits.compute(ip, (k, v) -> {
            if (v == null || now - v.windowStart > RATE_WINDOW_MS) return new RateLimit(1, now);
            v.count.incrementAndGet();
            return v;
        });
        return rl.count.get() <= RATE_LIMIT;
    }

    /**
     * SEC-4修复：X-Forwarded-For 防伪造
     *
     * 旧逻辑取 XFF 第一个 IP（最左侧），该值由客户端完全可控，可伪造任意 IP 绕过限流。
     * 新逻辑：
     *   - 取 remoteAddr（直接连接的 TCP 对端，不可伪造）作为限流 key
     *   - XFF 头仅用于日志记录，不作为安全决策依据
     *
     * 说明：若系统前置了可信的反向代理（Nginx/LB），需在代理层清除并重写 XFF，
     *       此时 remoteAddr 即为代理 IP，可在 application.yml 中配置信任代理。
     */
    private String getClientIp(HttpServletRequest request) {
        // SEC-4: 直接取 TCP 连接 IP（不可伪造），用于限流决策
        String remoteAddr = request.getRemoteAddr();

        // 若配置了可信代理且请求来自可信代理，才信任 XFF（取最右侧非空 IP）
        String trustedProxy = properties.getSecurity().getTrustedProxy();
        if (trustedProxy != null && !trustedProxy.isBlank()
                && remoteAddr != null && remoteAddr.equals(trustedProxy.trim())) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] ips = xff.split(",");
                // 取最右侧（代理最后添加的）非空 IP
                for (int i = ips.length - 1; i >= 0; i--) {
                    String ip = ips[i].trim();
                    if (!ip.isBlank()) return ip;
                }
            }
        }
        return remoteAddr;
    }

    private void writeError(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, msg)));
    }

    private static class RateLimit {
        final AtomicInteger count;
        final long windowStart;
        RateLimit(int count, long windowStart) {
            this.count = new AtomicInteger(count);
            this.windowStart = windowStart;
        }
    }
}