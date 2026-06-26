package com.opsmonitor.config;

import com.opsmonitor.model.User;
import com.opsmonitor.model.UserV2;
import com.opsmonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 注解权限拦截 AOP（配合 @RequirePermission）
 *
 * 实现原理：
 * 1. 拦截带有 @RequirePermission 的方法
 * 2. 从 RequestContextHolder 获取当前请求
 * 3. 从 request.getAttribute("currentUser") 获取已认证用户
 * 4. 调用 PermissionService 做 RBAC/ABAC 校验
 * 5. 校验失败抛出 SecurityException（由 GlobalExceptionHandler 转为 403）
 *
 * pom.xml 需要增加：
 *   <dependency>
 *     <groupId>org.springframework.boot</groupId>
 *     <artifactId>spring-boot-starter-aop</artifactId>
 *   </dependency>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final PermissionService permissionService;

    @Around("@annotation(com.opsmonitor.config.RequirePermission) || " +
            "@within(com.opsmonitor.config.RequirePermission)")
    public Object checkPermission(ProceedingJoinPoint pjp) throws Throwable {

        // 获取注解
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        RequirePermission annotation = method.getAnnotation(RequirePermission.class);
        if (annotation == null) {
            annotation = pjp.getTarget().getClass().getAnnotation(RequirePermission.class);
        }
        if (annotation == null || annotation.allowAnonymous()) {
            return pjp.proceed();
        }

        // 获取当前请求和用户
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return pjp.proceed(); // 非 HTTP 上下文（如定时任务），直接放行
        }

        User currentUser = (User) request.getAttribute("currentUser");
        if (currentUser == null) {
            throw new SecurityException("未登录或 Token 已失效");
        }

        // 角色快速通过（anyRole）
        String[] anyRole = annotation.anyRole();
        if (anyRole.length > 0) {
            for (String role : anyRole) {
                if (role.equals(currentUser.getRole())) {
                    return pjp.proceed();
                }
            }
        }

        // 逐一校验所需权限（AND 逻辑）
        for (String permString : annotation.value()) {
            String[] parts = permString.split(":", 2);
            String resource = parts[0];
            String action   = parts.length > 1 ? parts[1] : "read";

            boolean passed;

            // ABAC：own scope 需要检查资源所有者
            if ("own".equals(annotation.scope()) && !annotation.abacOwnerField().isBlank()) {
                String ownerId = extractOwnerField(pjp, request, annotation.abacOwnerField());
                passed = permissionService.hasPermissionLegacy(currentUser, permString)
                        || (ownerId != null && ownerId.equals(currentUser.getId()));
            } else {
                passed = permissionService.hasPermissionLegacy(currentUser, permString);
            }

            if (!passed) {
                log.warn("[PermissionAspect] 权限拒绝: user={} role={} need={} method={}",
                        currentUser.getUsername(), currentUser.getRole(),
                        permString, method.getName());
                throw new SecurityException(
                        "权限不足：需要 " + permString + " 权限");
            }
        }

        return pjp.proceed();
    }

    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从方法参数或 Request 中提取所有者字段值
     * 支持：PathVariable、RequestParam、RequestBody（Map/有 getter 的对象）
     */
    private String extractOwnerField(ProceedingJoinPoint pjp,
                                     HttpServletRequest request,
                                     String fieldName) {
        // 1. 先从 PathVariable / RequestParam 查
        String param = request.getParameter(fieldName);
        if (param != null) return param;

        // 2. 从方法参数对象中反射获取
        for (Object arg : pjp.getArgs()) {
            if (arg == null) continue;
            try {
                // 尝试 getXxx() 方法
                String getter = "get" + Character.toUpperCase(fieldName.charAt(0))
                        + fieldName.substring(1);
                Object val = arg.getClass().getMethod(getter).invoke(arg);
                if (val != null) return val.toString();
            } catch (Exception ignored) {}
            // 尝试 Map
            if (arg instanceof java.util.Map<?,?> map) {
                Object val = map.get(fieldName);
                if (val != null) return val.toString();
            }
        }
        return null;
    }
}