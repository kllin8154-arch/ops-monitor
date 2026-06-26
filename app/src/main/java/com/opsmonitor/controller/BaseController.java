package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Controller 抽象基类
 *
 * OPTIMIZED: 消灭全部 Controller 中重复的认证/权限样板代码。
 *
 * 改造前（每个 Controller 每个写操作方法各自重复）：
 *   User op = (User) request.getAttribute("currentUser");
 *   if (op == null) return ApiResponse.error(401, "未登录");
 *   if (!authService.hasPermission(op, "xxx")) return ApiResponse.error(403, "...");
 *   ...
 *   private User getUser(HttpServletRequest r) {...}          // 每个 Controller 一份
 *   private String getOperator(HttpServletRequest r) {...}    // 每个 Controller 一份
 *
 * 改造后（子类使用）：
 *   User op = requirePermission(request, "xxx:manage");
 *   if (op == null) return lastError();
 *
 * 统计：
 *   - 约 14 个 Controller 有 getUser/getOperator 私有方法（共 ~112 行重复代码）
 *   - 约 40+ 个方法有三行认证样板（共 ~120 行重复代码）
 *   - 总计消除约 232 行重复代码
 *
 * 安全说明：
 *   - 权限检查逻辑与原代码完全一致（均调用 authService.hasPermission）
 *   - 未引入任何新安全机制，也未降低现有机制
 *   - OPT-8 预留：后续迁移 PermissionService 只需改此一处
 */
@Slf4j
public abstract class BaseController {

    // OPTIMIZED: authService 由基类统一 @Autowired，子类无需重复声明
    // 子类通过 @RequiredArgsConstructor 注入自身依赖，authService 由基类负责
    @Autowired
    protected AuthService authService;

    // ==================== 认证 / 权限工具方法 ====================

    /**
     * OPTIMIZED: 获取当前登录用户（原各 Controller 各自定义 getUser() 私有方法）
     */
    protected User getUser(HttpServletRequest request) {
        return (User) request.getAttribute("currentUser");
    }

    /**
     * OPTIMIZED: 获取当前操作人名称（原各 Controller 各自定义 getOperator() 私有方法）
     */
    protected String getOperator(HttpServletRequest request) {
        User user = getUser(request);
        return user != null ? user.getUsername() : "anonymous";
    }

    /**
     * OPTIMIZED: 要求登录 + 指定权限，替代各方法中的 3 行样板代码
     *
     * 返回当前用户（非 null = 通过），null = 未登录或权限不足（调用 lastError() 获取响应）
     *
     * 用法：
     *   User op = requirePermission(request, "cmdb:manage");
     *   if (op == null) return lastError();
     *
     * @param permission 权限字符串：如 "server:manage" / "cmdb:manage" / "alerts:manage" / "*"
     */
    protected User requirePermission(HttpServletRequest request, String permission) {
        User user = getUser(request);
        if (user == null) {
            threadLocalError.set(ApiResponse.error(401, "未登录"));
            return null;
        }
        if (!authService.hasPermission(user, permission)) {
            String roleHint = "*".equals(permission) ? "ADMIN" : "OPS 或 ADMIN";
            threadLocalError.set(ApiResponse.error(403, "权限不足，需要 " + roleHint + " 角色"));
            log.warn("[Auth] 权限拒绝: user={} permission={} uri={}",
                    user.getUsername(), permission, request.getRequestURI());
            return null;
        }
        return user;
    }

    /**
     * OPTIMIZED: 仅要求登录（VIEWER 级只读场景）
     */
    protected User requireLogin(HttpServletRequest request) {
        User user = getUser(request);
        if (user == null) {
            threadLocalError.set(ApiResponse.error(401, "未登录"));
        }
        return user;
    }

    /**
     * 获取最近一次权限检查产生的错误响应
     * requirePermission/requireLogin 返回 null 时，通过此方法取出对应的 4xx 响应
     */
    @SuppressWarnings("unchecked")
    protected <T> ApiResponse<T> lastError() {
        ApiResponse<?> err = threadLocalError.get();
        threadLocalError.remove(); // 防止 ThreadLocal 泄漏
        return err != null ? (ApiResponse<T>) err : ApiResponse.error(500, "内部错误");
    }

    // ThreadLocal：每次请求独立，线程安全
    private static final ThreadLocal<ApiResponse<?>> threadLocalError = new ThreadLocal<>();

    // ==================== 语义化快捷方法 ====================

    /** 要求 OPS 或 ADMIN（最常用的写操作权限） */
    protected User requireOps(HttpServletRequest request) {
        return requirePermission(request, "server:manage");
    }

    /** 要求 ADMIN（最高权限） */
    protected User requireAdmin(HttpServletRequest request) {
        return requirePermission(request, "*");
    }

    /** 要求 VIEWER 及以上（只读操作） */
    protected User requireViewer(HttpServletRequest request) {
        return requirePermission(request, "metrics:query");
    }
}
