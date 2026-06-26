package com.opsmonitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.Permission;
import com.opsmonitor.model.Role;
import com.opsmonitor.model.UserV2;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 统一权限校验服务（RBAC + ABAC）
 *
 * 设计原则：
 * 1. RBAC：用户 → 多角色 → 权限集合（并集）
 * 2. ABAC：资源属性（ownerId）+ 用户属性（userId/tenantId）组合判断
 * 3. 细粒度：支持 own/all scope 区分
 * 4. 持久化：roles.json + users_v2.json（原子写入）
 * 5. 线程安全：ReadWriteLock（读多写少场景）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 角色定义（roleId → Role），读多写少，用 ReadWriteLock */
    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();

    /** 用户（userId → UserV2）*/
    private final ConcurrentHashMap<String, UserV2> users = new ConcurrentHashMap<>();

    /** 读写锁：批量操作时保证一致性 */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        loadRoles();
        loadUsers();
        // 确保内置角色始终存在
        ensureBuiltInRoles();
        log.info("[PermissionService] 初始化完成：{} 个角色，{} 个用户",
                roles.size(), users.size());
    }

    private void ensureBuiltInRoles() {
        roles.putIfAbsent("SUPER_ADMIN", Role.superAdmin());
        roles.putIfAbsent("ADMIN",       Role.admin());
        roles.putIfAbsent("OPS",         Role.ops());
        roles.putIfAbsent("VIEWER",      Role.viewer());
    }

    // ==================== 核心权限校验 ====================

    /**
     * 基础 RBAC 校验：用户是否拥有指定权限
     *
     * @param user       当前用户
     * @param resource   资源类型（如 "exporter"）
     * @param action     操作（如 "write"）
     * @return true = 有权限
     */
    public boolean hasPermission(UserV2 user, String resource, String action) {
        if (user == null || !user.isEnabled()) return false;
        Set<String> allPerms = resolvePermissions(user);
        return matchesAny(allPerms, resource, action, false);
    }

    /**
     * RBAC + ABAC 校验：同时考虑资源所有权
     *
     * @param user        当前用户
     * @param resource    资源类型
     * @param action      操作
     * @param resourceOwnerId 资源所有者 userId（ABAC 判断 "own" scope 用）
     * @return true = 有权限
     */
    public boolean hasPermission(UserV2 user, String resource, String action,
                                 String resourceOwnerId) {
        if (user == null || !user.isEnabled()) return false;
        Set<String> allPerms = resolvePermissions(user);

        // 1. 先检查全局权限（scope=all 或无 scope）
        if (matchesAny(allPerms, resource, action, false)) return true;

        // 2. 检查 own 权限：仅当用户是资源所有者
        if (resourceOwnerId != null && resourceOwnerId.equals(user.getId())) {
            return matchesAny(allPerms, resource, action, true);
        }

        return false;
    }

    /**
     * 跨租户校验
     *
     * @param user           当前用户
     * @param resource       资源类型
     * @param action         操作
     * @param targetTenantId 目标租户 ID
     */
    public boolean hasPermissionInTenant(UserV2 user, String resource, String action,
                                         String targetTenantId) {
        if (user == null || !user.isEnabled()) return false;

        // SUPER_ADMIN 跨租户无限制
        if (user.getRoles().contains("SUPER_ADMIN")) {
            return hasPermission(user, resource, action);
        }

        // 租户隔离：只能操作同租户资源
        if (!Objects.equals(user.getTenantId(), targetTenantId)) {
            log.warn("[ABAC] 跨租户访问拒绝: user={} tenantId={} targetTenant={}",
                    user.getUsername(), user.getTenantId(), targetTenantId);
            return false;
        }

        return hasPermission(user, resource, action);
    }

    /**
     * 兼容旧版 AuthService.hasPermission(User, String) 的桥接方法
     * 用于现有 Controller 平滑迁移
     */
    public boolean hasPermissionLegacy(com.opsmonitor.model.User user, String permString) {
        if (user == null) return false;
        // 旧版权限字符串直接匹配
        if ("*".equals(permString)) {
            return "ADMIN".equals(user.getRole()) || "SUPER_ADMIN".equals(user.getRole());
        }
        String[] parts = permString.split(":", 2);
        String res = parts[0];
        String act = parts.length > 1 ? parts[1] : "read";
        // 根据旧版 role 获取权限集
        Role role = roles.get(user.getRole());
        if (role == null) return false;
        return matchesAny(role.getPermissions(), res, act, false);
    }

    // ==================== 权限解析 ====================

    /**
     * 解析用户所有有效权限（多角色并集 + 额外权限）
     *
     * 线程安全：ConcurrentHashMap + 只读操作，无需加锁
     */
    public Set<String> resolvePermissions(UserV2 user) {
        Set<String> all = new HashSet<>();

        // 1. 收集所有角色的权限（多角色取并集）
        for (String roleId : user.getRoles()) {
            Role role = roles.get(roleId);
            if (role != null) {
                all.addAll(role.getPermissions());
            }
        }

        // 2. 叠加用户级额外权限（可用于特权授予）
        if (user.getExtraPermissions() != null) {
            all.addAll(user.getExtraPermissions());
        }

        return Collections.unmodifiableSet(all);
    }

    // ==================== 角色管理 ====================

    public Role getRole(String roleId) {
        return roles.get(roleId);
    }

    public List<Role> listRoles() {
        return new ArrayList<>(roles.values());
    }

    public Role createRole(Role role) {
        if (role.isBuiltIn()) throw new IllegalArgumentException("不能创建内置角色");
        rwLock.writeLock().lock();
        try {
            if (roles.containsKey(role.getId()))
                throw new IllegalArgumentException("角色 ID 已存在: " + role.getId());
            roles.put(role.getId(), role);
            saveRoles();
            log.info("[RBAC] 创建自定义角色: {}", role.getId());
            return role;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean deleteRole(String roleId) {
        Role role = roles.get(roleId);
        if (role == null) return false;
        if (role.isBuiltIn()) throw new IllegalArgumentException("内置角色不允许删除");
        rwLock.writeLock().lock();
        try {
            roles.remove(roleId);
            saveRoles();
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 为角色增加权限 */
    public void grantPermissionToRole(String roleId, String permString) {
        Role role = roles.get(roleId);
        if (role == null) throw new IllegalArgumentException("角色不存在: " + roleId);
        if (role.isBuiltIn()) throw new IllegalArgumentException("内置角色权限不可修改");
        rwLock.writeLock().lock();
        try {
            role.getPermissions().add(permString);
            saveRoles();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ==================== 用户角色管理 ====================

    /** 为用户添加角色（多角色支持） */
    public void assignRole(String userId, String roleId) {
        UserV2 user = users.get(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在: " + userId);
        if (!roles.containsKey(roleId)) throw new IllegalArgumentException("角色不存在: " + roleId);
        rwLock.writeLock().lock();
        try {
            user.getRoles().add(roleId);
            saveUsers();
            log.info("[RBAC] 授予角色: user={} role={}", user.getUsername(), roleId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 撤销用户的角色 */
    public void revokeRole(String userId, String roleId) {
        UserV2 user = users.get(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在: " + userId);
        rwLock.writeLock().lock();
        try {
            user.getRoles().remove(roleId);
            saveUsers();
            log.info("[RBAC] 撤销角色: user={} role={}", user.getUsername(), roleId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 为用户授予额外权限（ABAC 扩展） */
    public void grantExtraPermission(String userId, String permString) {
        UserV2 user = users.get(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        rwLock.writeLock().lock();
        try {
            user.getExtraPermissions().add(permString);
            saveUsers();
            log.info("[ABAC] 授予额外权限: user={} perm={}", user.getUsername(), permString);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 判断权限集合中是否有匹配项
     *
     * @param permissions  权限集合
     * @param resource     目标资源
     * @param action       目标操作
     * @param ownScopeOnly 是否只匹配 own 范围权限
     */
    private boolean matchesAny(Set<String> permissions, String resource,
                               String action, boolean ownScopeOnly) {
        for (String p : permissions) {
            if ("*".equals(p)) return true;  // 超级权限
            Permission perm = Permission.parse(p);
            if (!perm.matches(resource, action)) continue;
            if (ownScopeOnly) {
                if (perm.isOwnerScoped()) return true;
            } else {
                if (perm.isGlobalScoped()) return true;
            }
        }
        return false;
    }

    // ==================== 持久化 ====================

    private Path getDataDir() {
        String workDir = properties.getCompose().getWorkDir();
        if (workDir == null || workDir.isBlank()) {
            workDir = System.getProperty("user.dir", ".") + "/docker";
        }
        return Paths.get(workDir, "..", "data").normalize();
    }

    private Path getRolesFile() {
        return getDataDir().resolve("roles.json");
    }

    private Path getUsersV2File() {
        return getDataDir().resolve("users_v2.json");
    }

    private void loadRoles() {
        Path f = getRolesFile();
        if (!Files.exists(f)) return;
        try {
            List<Role> list = mapper.readValue(f.toFile(), new TypeReference<>() {});
            list.forEach(r -> roles.put(r.getId(), r));
            log.info("[RBAC] 已加载 {} 个角色", roles.size());
        } catch (IOException e) {
            log.error("[RBAC] 加载 roles.json 失败: {}", e.getMessage());
        }
    }

    private void loadUsers() {
        Path f = getUsersV2File();
        if (!Files.exists(f)) return;
        try {
            List<UserV2> list = mapper.readValue(f.toFile(), new TypeReference<>() {});
            list.forEach(u -> users.put(u.getId(), u));
            log.info("[RBAC] 已加载 {} 个用户(v2)", users.size());
        } catch (IOException e) {
            log.error("[RBAC] 加载 users_v2.json 失败: {}", e.getMessage());
        }
    }

    /** 原子写入 roles.json */
    private void saveRoles() {
        atomicWrite(getRolesFile(), new ArrayList<>(roles.values()));
    }

    /** 原子写入 users_v2.json */
    private void saveUsers() {
        atomicWrite(getUsersV2File(), new ArrayList<>(users.values()));
    }

    private void atomicWrite(Path target, Object data) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            mapper.writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[RBAC] 持久化失败 {}: {}", target.getFileName(), e.getMessage());
        }
    }
}