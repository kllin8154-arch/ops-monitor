package com.opsmonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户模型 v2（支持多角色）
 *
 * 与现有 User.java 的区别：
 * - roles 改为 Set<String>，支持用户同时持有多个角色
 * - 新增 extraPermissions：用户级别的额外权限（超出角色范围的特殊授权）
 * - 保留 passwordHash、enabled 等现有字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserV2 {

    private String id;
    private String username;
    /** P0-1 fix: 禁止序列化到 API 响应 */
    @JsonIgnore
    private String passwordHash;
    private String displayName;

    /**
     * 多角色支持：用户可同时拥有多个角色
     * 权限取所有角色权限的并集
     * 示例：{"OPS", "ALERT_MANAGER"}
     */
    @Builder.Default
    private Set<String> roles = new HashSet<>();

    /**
     * 用户级别额外权限（ABAC 扩展点）
     * 优先级高于角色权限
     * 示例：{"config:write:all"}（OPS 用户特批全局配置写权限）
     */
    @Builder.Default
    private Set<String> extraPermissions = new HashSet<>();

    /**
     * 所属租户（ABAC：跨租户隔离）
     */
    @Builder.Default
    private String tenantId = "default";

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    /** 是否为旧版单角色格式（迁移兼容用） */
    private String legacyRole;
}