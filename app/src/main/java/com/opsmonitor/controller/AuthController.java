package com.opsmonitor.controller;

import com.opsmonitor.config.ApiSafetyFilter;
import com.opsmonitor.config.InputValidator;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuditLogService;
import com.opsmonitor.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证 API v2.5-sec（FIX: httpOnly Cookie + Token 双通道）
 *
 * FIX-TOKEN-1: 登录成功后同时返回：
 *   - JSON body 中的 token（兼容现有前端/API 客户端）
 *   - Set-Cookie: ops_token=xxx; HttpOnly; SameSite=Strict; Path=/（防 XSS 窃取）
 * FIX-TOKEN-2: 登出时同时清除 Cookie
 * FIX-TOKEN-3: SecurityInterceptor 同时接受 Cookie 和 Bearer Header（向后兼容）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService     authService;
    private final AuditLogService auditLog;
    private final ApiSafetyFilter safetyFilter;

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "OPS", "VIEWER");

    /** FIX-TOKEN-1: Cookie 名称（与前端保持一致） */
    private static final String TOKEN_COOKIE_NAME = "ops_token";
    /** Token 有效期 24 小时（秒） */
    private static final int TOKEN_COOKIE_MAX_AGE = 24 * 60 * 60;

    // ==================== 登录 / 登出 ====================

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,                             // v2.10 P1-04:注入 request 供 Secure 检测
            HttpServletResponse response) {                         // FIX-TOKEN-1: 注入 response

        String username = body.get("username");
        if (username == null || username.isBlank()) {
            return ApiResponse.error(400, "用户名不能为空");
        }
        try {
            safetyFilter.checkLoginThrottle(username);
        } catch (IllegalArgumentException e) {
            auditLog.logFail("system", "LOGIN_BLOCKED", username, e.getMessage());
            return ApiResponse.error(429, e.getMessage());
        }
        try {
            String token = authService.login(username, body.get("password"));
            safetyFilter.clearLoginFailure(username);
            auditLog.logSuccess(username, "LOGIN", username);

            // FIX-TOKEN-1: 设置 httpOnly Cookie (v2.10 P1-04:动态 Secure)
            setTokenCookie(request, response, token, TOKEN_COOKIE_MAX_AGE);

            // v2.22: 返回用户角色，供前端 RBAC 隐藏控制
            String role = authService.listUsers().stream()
                    .filter(u -> u.getUsername().equals(username))
                    .findFirst().map(User::getRole).orElse("VIEWER");
            return ApiResponse.ok(Map.of("token", token, "role", role));
        } catch (IllegalArgumentException e) {
            safetyFilter.recordLoginFailure(username);
            auditLog.logFail("system", "LOGIN_FAIL", username, e.getMessage());
            return ApiResponse.error(401, "用户名或密码错误");
        }
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpServletRequest request, HttpServletResponse response) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7));
        }
        // FIX-TOKEN-2: 同时清除 Cookie 中的 Token
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie c : cookies) {
                if (TOKEN_COOKIE_NAME.equals(c.getName())) {
                    authService.logout(c.getValue());
                    break;
                }
            }
        }
        // FIX-TOKEN-2: 设置过期 Cookie 清除浏览器存储 (v2.10 P1-04:动态 Secure)
        setTokenCookie(request, response, "", 0);

        auditLog.logSuccess(getOperator(request), "LOGOUT", "token revoked");
        return ApiResponse.ok("已注销");
    }

    // ==================== 用户管理 ====================

    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers(HttpServletRequest request) {
        User me = getUser(request);
        if (me == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(me, "server:manage")) {
            return ApiResponse.error(403, "权限不足，需要 OPS 或 ADMIN 角色");
        }
        return ApiResponse.ok(authService.listUsers());
    }

    @PostMapping("/users")
    public ApiResponse<User> createUser(@RequestBody Map<String, String> body,
                                        HttpServletRequest request) {
        User me = getUser(request);
        if (me == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(me, "*")) {
            return ApiResponse.error(403, "权限不足，仅 ADMIN 可创建用户");
        }

        String username    = body.get("username");
        String password    = body.get("password");
        String role        = body.get("role");
        String displayName = body.get("displayName");

        if (username == null || username.isBlank()) {
            return ApiResponse.error(400, "用户名不能为空");
        }
        if (!username.matches("^[a-zA-Z0-9_\\-]{3,32}$")) {
            return ApiResponse.error(400, "用户名格式不合法（3-32位字母/数字/下划线/中划线）");
        }
        if (password == null || password.length() < 8) {
            // FIX: 密码最小长度从 6 提升至 8 位
            return ApiResponse.error(400, "密码长度不能少于8位");
        }
        if (role != null && !ALLOWED_ROLES.contains(role.toUpperCase())) {
            return ApiResponse.error(400, "角色不合法，允许: ADMIN / OPS / VIEWER");
        }
        if (displayName != null) {
            displayName = InputValidator.sanitize(displayName);
        }

        try {
            User user = authService.createUser(username, password,
                    role != null ? role.toUpperCase() : "VIEWER", displayName);
            auditLog.logSuccess(me.getUsername(), "CREATE_USER",
                    user.getUsername() + "(" + user.getRole() + ")");
            return ApiResponse.ok("用户已创建", user);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<String> deleteUser(@PathVariable String id,
                                          HttpServletRequest request) {
        User me = getUser(request);
        if (me == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(me, "*")) {
            return ApiResponse.error(403, "权限不足，仅 ADMIN 可删除用户");
        }
        if (me.getId().equals(id)) {
            return ApiResponse.error(400, "不允许删除当前登录账户");
        }
        List<User> allUsers = authService.listUsers();
        boolean targetIsAdmin = allUsers.stream()
                .anyMatch(u -> u.getId().equals(id) && "ADMIN".equals(u.getRole()));
        if (targetIsAdmin) {
            long remainingAdmins = allUsers.stream()
                    .filter(u -> "ADMIN".equals(u.getRole()) && !u.getId().equals(id))
                    .count();
            if (remainingAdmins == 0) {
                return ApiResponse.error(400, "不允许删除最后一个 ADMIN 账户");
            }
        }
        try {
            authService.deleteUser(id);
            auditLog.logSuccess(me.getUsername(), "DELETE_USER", id);
            return ApiResponse.ok("用户已删除");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @PostMapping("/change-password")
    public ApiResponse<String> changePassword(@RequestBody Map<String, String> body,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {     // FIX-TOKEN-2
        User me = getUser(request);
        if (me == null) return ApiResponse.error(401, "未登录");

        String oldPwd = body.get("oldPassword");
        String newPwd = body.get("newPassword");

        if (oldPwd == null || newPwd == null) {
            return ApiResponse.error(400, "请填写原密码和新密码");
        }
        // FIX: 密码最小长度从 6 提升至 8
        if (newPwd.length() < 8) {
            return ApiResponse.error(400, "新密码长度不能少于8位");
        }
        try {
            authService.login(me.getUsername(), oldPwd);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(401, "原密码错误");
        }
        authService.changePassword(me.getId(), newPwd);

        // FIX-TOKEN-2: 清除旧 Token（Bearer + Cookie）
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7));
        }
        setTokenCookie(request, response, "", 0); // 清除 Cookie (v2.10 P1-04:动态 Secure)

        auditLog.logSuccess(me.getUsername(), "CHANGE_PASSWORD", me.getUsername());
        return ApiResponse.ok("密码已修改，请重新登录");
    }

    @GetMapping("/audit-log")
    public ApiResponse<List<AuditLogService.AuditEntry>> auditLog(
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        User me = getUser(request);
        if (me != null && !authService.hasPermission(me, "server:manage")) {
            return ApiResponse.error(403, "权限不足");
        }
        return ApiResponse.ok(auditLog.list(Math.min(limit, 500)));
    }

    // ==================== 工具方法 ====================

    private User getUser(HttpServletRequest request) {
        return (User) request.getAttribute("currentUser");
    }

    private String getOperator(HttpServletRequest request) {
        User u = getUser(request);
        return u != null ? u.getUsername() : "anonymous";
    }

    /**
     * FIX-TOKEN-1: 统一的 Cookie 设置方法
     * HttpOnly: 禁止 JavaScript 读取（防 XSS 窃取）
     * SameSite=Strict: 禁止跨站请求携带（防 CSRF）
     *
     * v2.10 P1-04 修复:动态判定 Secure 属性
     *   原代码注释说"由反代统一处理",但这依赖部署环境且不可靠
     *   新逻辑:根据 request.isSecure() 或 X-Forwarded-Proto=https 动态添加 Secure
     *   影响:HTTPS 场景 Cookie 防降级攻击;HTTP 场景仍正常工作(不加 Secure)
     */
    private void setTokenCookie(HttpServletRequest request, HttpServletResponse response, String value, int maxAge) {
        // 使用 Set-Cookie Header 手动构建，确保 SameSite 属性（Servlet API 不直接支持 SameSite）
        StringBuilder cookieHeader = new StringBuilder();
        cookieHeader.append(TOKEN_COOKIE_NAME).append("=").append(value).append("; ");
        cookieHeader.append("Path=/; ");
        cookieHeader.append("HttpOnly; ");
        cookieHeader.append("SameSite=Strict; ");
        cookieHeader.append("Max-Age=").append(maxAge);

        // v2.10 P1-04:动态检测 HTTPS,是则加 Secure 标志防降级
        if (isSecureRequest(request)) {
            cookieHeader.append("; Secure");
        }

        response.addHeader("Set-Cookie", cookieHeader.toString());
    }

    /**
     * v2.10 P1-04:检测请求是否通过 HTTPS
     * 支持两种场景:
     *   1. 直接 HTTPS:request.isSecure() 返回 true
     *   2. 反向代理 HTTPS:X-Forwarded-Proto 头为 https(Nginx/Traefik 常见)
     */
    private boolean isSecureRequest(HttpServletRequest request) {
        if (request == null) return false;
        if (request.isSecure()) return true;
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.trim());
    }
}