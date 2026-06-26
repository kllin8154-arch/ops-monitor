package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RBAC 用户模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private String id;
    private String username;

    /**
     * v2.11 BUG-1 修复：移除 @JsonIgnore
     *
     * 问题：@JsonIgnore 导致 Jackson 序列化 users.json 时跳过 passwordHash，
     *       重启后加载的用户 passwordHash=null，BCrypt 验证失败，所有用户无法登录。
     *
     * 修复：移除 @JsonIgnore，让 passwordHash 正常写入 users.json 文件。
     *
     * API 安全保障：所有 Controller 返回 User 对象时，均通过
     *   AuthService.sanitizedCopy() 将 passwordHash 替换为 "***"，
     *   确保 BCrypt 哈希不会泄露到 API 响应中。
     */
    private String passwordHash;

    private String displayName;

    /** 角色: ADMIN / OPS / VIEWER */
    @Builder.Default
    private String role = "VIEWER";

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private long createdAt = System.currentTimeMillis();
}