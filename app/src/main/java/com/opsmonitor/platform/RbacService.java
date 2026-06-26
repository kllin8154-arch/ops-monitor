package com.opsmonitor.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RBAC 权限服务 (10D-5)
 *
 * 三角色模型：
 * - ADMIN：全部权限（跨租户）
 * - OPERATOR：读写（本租户内）
 * - VIEWER：只读（本租户内）
 *
 * 权限维度：
 * - tenant：租户隔离
 * - resource.kind：资源类型权限
 * - action：read / write / delete / admin
 *
 * 与 AuthService 集成：登录后返回 role + tenant
 */
@Slf4j
@Service
public class RbacService {

    /** 用户→角色映射: username → RoleBinding */
    private final Map<String, RoleBinding> roleBindings = new ConcurrentHashMap<>();

    /** 角色→权限定义 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", Set.of("read", "write", "delete", "admin", "cross_tenant"),
            "OPERATOR", Set.of("read", "write", "delete"),
            "VIEWER", Set.of("read")
    );

    public RbacService() {
        // 默认 admin 绑定
        roleBindings.put("admin", RoleBinding.builder()
                .username("admin").role("ADMIN").tenant("*")
                .build());
    }

    /**
     * 绑定用户角色
     */
    public void bindRole(String username, String role, String tenant) {
        if (!ROLE_PERMISSIONS.containsKey(role)) {
            throw new IllegalArgumentException("无效角色: " + role);
        }
        roleBindings.put(username, RoleBinding.builder()
                .username(username).role(role).tenant(tenant)
                .build());
        log.info("[RBAC] 绑定角色: {} → {} (tenant={})", username, role, tenant);
    }

    /**
     * 检查权限
     */
    public boolean checkPermission(String username, String action, String targetTenant) {
        RoleBinding binding = roleBindings.get(username);
        if (binding == null) return false;

        Set<String> perms = ROLE_PERMISSIONS.getOrDefault(binding.getRole(), Set.of());

        // ADMIN 跨租户
        if (perms.contains("cross_tenant")) return perms.contains(action);

        // 普通用户：必须在同租户内
        if (!binding.getTenant().equals(targetTenant) && !"*".equals(binding.getTenant())) {
            return false;
        }
        return perms.contains(action);
    }

    /**
     * 获取用户的角色绑定
     */
    public RoleBinding getBinding(String username) {
        return roleBindings.get(username);
    }

    /**
     * 权限校验（抛异常版）
     */
    public void enforce(String username, String action, String targetTenant) {
        if (!checkPermission(username, action, targetTenant)) {
            throw new SecurityException(String.format(
                    "权限不足: user=%s, action=%s, tenant=%s", username, action, targetTenant));
        }
    }

    /**
     * 列出所有角色绑定
     */
    public List<RoleBinding> listBindings() {
        return new ArrayList<>(roleBindings.values());
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RoleBinding {
        private String username;
        private String role;
        private String tenant;
    }
}