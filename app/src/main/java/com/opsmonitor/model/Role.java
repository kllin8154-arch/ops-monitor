package com.opsmonitor.model;

import lombok.*;
import java.util.HashSet;
import java.util.Set;

/**
 * 角色模型（支持多角色用户）
 *
 * 内置角色：
 *   SUPER_ADMIN  - 超级管理员（所有权限，跨租户）
 *   ADMIN        - 管理员（用户管理 + 所有资源管理）
 *   OPS          - 运维（资源管理，不能管用户）
 *   VIEWER       - 只读
 *
 * 支持自定义角色（如 ALERT_MANAGER、CONFIG_REVIEWER）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    /** 角色ID（英文大写，如 ADMIN / OPS_LEAD） */
    private String id;

    /** 显示名称 */
    private String displayName;

    /** 该角色拥有的权限列表 */
    @Builder.Default
    private Set<String> permissions = new HashSet<>();

    /** 是否为系统内置角色（内置角色不允许删除） */
    @Builder.Default
    private boolean builtIn = false;

    /** 创建时间 */
    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    // ===== 内置角色定义 =====

    public static Role superAdmin() {
        return Role.builder()
                .id("SUPER_ADMIN").displayName("超级管理员").builtIn(true)
                .permissions(Set.of("*"))
                .build();
    }

    public static Role admin() {
        return Role.builder()
                .id("ADMIN").displayName("管理员").builtIn(true)
                .permissions(Set.of(
                        "user:read", "user:write", "user:delete",
                        "exporter:read", "exporter:write", "exporter:delete",
                        "server:read", "server:write", "server:delete",
                        "container:read", "container:write", "container:delete",
                        "alert:read", "alert:ack", "alert:silence",
                        "config:read", "config:write:all", "config:rollback",
                        "cmdb:read", "cmdb:write", "cmdb:delete",
                        "tenant:read", "tenant:write",
                        "agent:read", "agent:write"
                ))
                .build();
    }

    public static Role ops() {
        return Role.builder()
                .id("OPS").displayName("运维工程师").builtIn(true)
                .permissions(Set.of(
                        "exporter:read", "exporter:write", "exporter:delete",
                        "server:read", "server:write",
                        "container:read", "container:write",
                        "alert:read", "alert:ack",
                        "config:read", "config:write:own",
                        "cmdb:read", "cmdb:write",
                        "agent:read"
                ))
                .build();
    }

    public static Role viewer() {
        return Role.builder()
                .id("VIEWER").displayName("只读用户").builtIn(true)
                .permissions(Set.of(
                        "exporter:read", "server:read", "container:read",
                        "alert:read", "config:read", "cmdb:read", "agent:read"
                ))
                .build();
    }
}