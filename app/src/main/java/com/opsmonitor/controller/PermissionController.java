package com.opsmonitor.controller;

import com.opsmonitor.config.RequirePermission;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.Role;
import com.opsmonitor.model.User;
import com.opsmonitor.model.UserV2;
import com.opsmonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限管理 API
 *
 * 使用 @RequirePermission 注解示例：
 *
 * ① 基础 RBAC
 *    @RequirePermission("exporter:write")
 *
 * ② 多权限 AND
 *    @RequirePermission({"server:write", "config:read"})
 *
 * ③ 角色快速通过
 *    @RequirePermission(value = "user:delete", anyRole = {"ADMIN", "SUPER_ADMIN"})
 *
 * ④ ABAC own scope（只能改自己的配置）
 *    @RequirePermission(value = "config:write", scope = "own", abacOwnerField = "createdBy")
 *
 * ⑤ 类级别注解（整个 Controller 都需要此权限）
 *    @RequirePermission("cmdb:read")
 *    class CmdbController { ... }
 */
@RestController
@RequestMapping("/api/v2/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    // ===== 角色管理（仅 ADMIN）=====

    @GetMapping("/roles")
    @RequirePermission(value = "user:read", anyRole = {"ADMIN", "SUPER_ADMIN"})
    public ApiResponse<List<Role>> listRoles() {
        return ApiResponse.ok(permissionService.listRoles());
    }

    @PostMapping("/roles")
    @RequirePermission(value = "user:write", anyRole = {"SUPER_ADMIN"})
    public ApiResponse<Role> createRole(@RequestBody Role role) {
        return ApiResponse.ok("角色已创建", permissionService.createRole(role));
    }

    @DeleteMapping("/roles/{roleId}")
    @RequirePermission(anyRole = {"SUPER_ADMIN"})
    public ApiResponse<String> deleteRole(@PathVariable String roleId) {
        permissionService.deleteRole(roleId);
        return ApiResponse.ok("角色已删除");
    }

    @PostMapping("/roles/{roleId}/permissions")
    @RequirePermission(anyRole = {"SUPER_ADMIN"})
    public ApiResponse<String> grantPermission(@PathVariable String roleId,
                                               @RequestBody Map<String, String> body) {
        permissionService.grantPermissionToRole(roleId, body.get("permission"));
        return ApiResponse.ok("权限已授予");
    }

    // ===== 用户角色管理（ADMIN）=====

    @PostMapping("/users/{userId}/roles")
    @RequirePermission(value = "user:write", anyRole = {"ADMIN", "SUPER_ADMIN"})
    public ApiResponse<String> assignRole(@PathVariable String userId,
                                          @RequestBody Map<String, String> body) {
        permissionService.assignRole(userId, body.get("roleId"));
        return ApiResponse.ok("角色已分配");
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @RequirePermission(value = "user:write", anyRole = {"ADMIN", "SUPER_ADMIN"})
    public ApiResponse<String> revokeRole(@PathVariable String userId,
                                          @PathVariable String roleId) {
        permissionService.revokeRole(userId, roleId);
        return ApiResponse.ok("角色已撤销");
    }

    @PostMapping("/users/{userId}/extra-permissions")
    @RequirePermission(anyRole = {"SUPER_ADMIN"})
    public ApiResponse<String> grantExtra(@PathVariable String userId,
                                          @RequestBody Map<String, String> body) {
        permissionService.grantExtraPermission(userId, body.get("permission"));
        return ApiResponse.ok("额外权限已授予");
    }

    // ===== 权限查询（当前用户）=====

    @GetMapping("/my-permissions")
    public ApiResponse<Set<String>> myPermissions(HttpServletRequest request) {
        User legacy = (User) request.getAttribute("currentUser");
        if (legacy == null) return ApiResponse.error(401, "未登录");
        // 此处演示：实际项目中需要从 PermissionService 加载 UserV2
        return ApiResponse.ok(permissionService
                .getRole(legacy.getRole()) != null
                ? permissionService.getRole(legacy.getRole()).getPermissions()
                : Set.of());
    }
}