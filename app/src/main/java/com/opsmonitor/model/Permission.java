package com.opsmonitor.model;

import lombok.*;
import java.util.Set;

/**
 * 权限模型
 *
 * 格式统一为：resource:action[:scope]
 * 例：
 *   exporter:read          - 读取 Exporter
 *   exporter:write         - 创建/修改 Exporter
 *   exporter:delete        - 删除 Exporter
 *   server:manage          - 管理服务器（含 read+write+delete）
 *   alert:read             - 查看告警
 *   alert:ack              - 确认告警
 *   config:write:own       - 只能修改自己提交的配置
 *   config:write:all       - 可修改任意配置
 *   *                      - 超级权限（ADMIN 专用）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Permission {

    /** 权限标识：resource:action[:scope] */
    private String id;

    /** 显示名称 */
    private String displayName;

    /** 资源类型（exporter / server / alert / config / container / cmdb ...）*/
    private String resource;

    /** 操作（read / write / delete / manage / ack ...）*/
    private String action;

    /**
     * 范围（可选）
     *   own  - 仅限自己的资源（ABAC）
     *   all  - 所有资源
     *   null - 不限制范围
     */
    private String scope;

    // ===== 工厂方法 =====

    /** 解析 "resource:action" 或 "resource:action:scope" */
    public static Permission parse(String permString) {
        if ("*".equals(permString)) {
            return Permission.builder()
                    .id("*").resource("*").action("*").displayName("超级权限").build();
        }
        String[] parts = permString.split(":", 3);
        return Permission.builder()
                .id(permString)
                .resource(parts[0])
                .action(parts.length > 1 ? parts[1] : "*")
                .scope(parts.length > 2 ? parts[2] : null)
                .displayName(permString)
                .build();
    }

    /** 判断是否匹配某个请求的权限 */
    public boolean matches(String resource, String action) {
        if ("*".equals(this.resource)) return true;
        if (!this.resource.equals(resource)) return false;
        return "*".equals(this.action) || this.action.equals(action);
    }

    public boolean isOwnerScoped() { return "own".equals(scope); }
    public boolean isGlobalScoped() { return scope == null || "all".equals(scope); }
}