package com.opsmonitor.config;

import java.lang.annotation.*;

/**
 * 注解式权限控制
 *
 * 使用示例：
 *   @RequirePermission("exporter:write")
 *   @RequirePermission(value = "config:write", scope = "own", abacOwnerField = "createdBy")
 *   @RequirePermission(value = "container:delete", anyRole = {"ADMIN"})
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /**
     * 所需权限（resource:action 格式）
     * 支持多个（逻辑 AND：所有权限都要满足）
     */
    String[] value() default {};

    /**
     * 范围限制（可选）
     *   "own"  - 只能访问自己的资源（需配合 abacOwnerField）
     *   "all"  - 无范围限制
     *   ""     - 不限制（默认）
     */
    String scope() default "";

    /**
     * ABAC 所有者字段名（scope="own" 时使用）
     * 指定请求体/PathVariable 中哪个字段代表资源所有者 ID
     * 例：abacOwnerField = "createdBy" 表示从请求体取 createdBy 字段
     */
    String abacOwnerField() default "";

    /**
     * 备选角色（逻辑 OR）：拥有其中任一角色即可通过，不做权限细粒度检查
     * 优先级低于 value()
     */
    String[] anyRole() default {};

    /**
     * 是否允许匿名（不需要登录）
     * 默认 false（需要登录）
     */
    boolean allowAnonymous() default false;
}