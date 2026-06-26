package com.opsmonitor.controller;

import com.opsmonitor.config.InputValidator;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.platform.*;
import com.opsmonitor.platform.model.ManagedResource;
import com.opsmonitor.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 平台层 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser/getOperator 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板从 3 行 → 2 行
 *   改造前：191 行 → 改造后：164 行（节省 27 行）
 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController extends BaseController {

    private final ResourceRegistry    resourceRegistry;
    private final ConfigCenterService configCenter;
    private final AgentCommandService commandService;
    private final TenantService       tenantService;
    private final AuditLogService     auditLog;

    // ==================== 资源 ====================

    @GetMapping("/resources")
    public ApiResponse<List<ManagedResource>> listResources(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String tenant) {
        List<ManagedResource> list;
        if (kind != null && tenant != null) list = resourceRegistry.listByTenantAndKind(tenant, kind);
        else if (kind != null)              list = resourceRegistry.listByKind(kind);
        else                                list = resourceRegistry.listByKind("Project");
        return ApiResponse.ok(list);
    }

    @GetMapping("/resources/stats")
    public ApiResponse<Map<String, Long>> resourceStats() {
        return ApiResponse.ok(resourceRegistry.countByKind());
    }

    // ==================== 配置中心 ====================

    @GetMapping("/config")
    public ApiResponse<Map<String, Integer>> listConfigs() {
        return ApiResponse.ok(configCenter.listConfigs());
    }

    @GetMapping("/config/{name}")
    public ApiResponse<String> readConfig(@PathVariable String name) {
        String safeName = InputValidator.sanitize(name);
        String content  = configCenter.readConfig(safeName);
        return content != null ? ApiResponse.ok(content) : ApiResponse.error(404, "配置不存在");
    }

    @GetMapping("/config/{name}/history")
    public ApiResponse<List<ConfigCenterService.ConfigVersion>> configHistory(
            @PathVariable String name) {
        return ApiResponse.ok(configCenter.getHistory(InputValidator.sanitize(name)));
    }

    @PostMapping("/config/{name}")
    public ApiResponse<ConfigCenterService.ConfigVersion> commitConfig(
            @PathVariable String name,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String safeName = InputValidator.sanitize(name);
        String content  = body.get("content");
        String reason   = body.getOrDefault("reason", "API 提交");
        ConfigCenterService.ConfigVersion ver = configCenter.commitConfig(safeName, content, reason);
        auditLog.logSuccess(getOperator(request), "CONFIG_COMMIT",
                safeName + " v" + ver.getVersion());
        return ApiResponse.ok(ver);
    }

    @PostMapping("/config/{name}/rollback/{version}")
    public ApiResponse<ConfigCenterService.ConfigVersion> rollbackConfig(
            @PathVariable String name,
            @PathVariable int version,
            HttpServletRequest request) {
        String safeName = InputValidator.sanitize(name);
        ConfigCenterService.ConfigVersion ver = configCenter.rollback(safeName, version);
        auditLog.logSuccess(getOperator(request), "CONFIG_ROLLBACK",
                safeName + " → v" + version);
        return ApiResponse.ok(ver);
    }

    // ==================== Agent 命令 ====================

    @PostMapping("/agents/{id}/command")
    public ApiResponse<AgentCommandService.AgentCommand> sendCommand(
            @PathVariable String id,
            @RequestBody AgentCommandService.AgentCommand command,
            HttpServletRequest request) {
        // OPTIMIZED: 3 行样板 → 2 行
        User operator = requireOps(request);
        if (operator == null) return lastError();

        AgentCommandService.AgentCommand sent = commandService.sendCommand(id, command);
        auditLog.logSuccess(getOperator(request), "AGENT_COMMAND",
                id + ":" + command.getType());
        return ApiResponse.ok(sent);
    }

    @GetMapping("/agents/{id}/commands")
    public ApiResponse<List<AgentCommandService.AgentCommand>> pullCommands(
            @PathVariable String id,
            HttpServletRequest request) {
        User operator = requireOps(request);
        if (operator == null) return lastError();
        return ApiResponse.ok(commandService.pullCommands(id));
    }

    @PostMapping("/agents/{id}/config")
    public ApiResponse<AgentCommandService.AgentCommand> sendAgentConfig(
            @PathVariable String id,
            @RequestBody AgentCommandService.AgentConfigDTO config,
            HttpServletRequest request) {
        AgentCommandService.AgentCommand sent = commandService.sendConfig(id, config);
        auditLog.logSuccess(getOperator(request), "AGENT_CONFIG_PUSH", id);
        return ApiResponse.ok(sent);
    }

    @PostMapping("/agents/broadcast")
    public ApiResponse<Integer> broadcast(
            @RequestBody AgentCommandService.AgentCommand command,
            HttpServletRequest request) {
        User operator = requireAdmin(request);
        if (operator == null) return lastError();

        int count = commandService.broadcast(command);
        auditLog.logSuccess(getOperator(request), "AGENT_BROADCAST",
                command.getType() + " → " + count + " agents");
        return ApiResponse.ok("已广播", count);
    }

    // ==================== 多租户 ====================

    @GetMapping("/tenants")
    public ApiResponse<List<TenantService.Tenant>> listTenants() {
        return ApiResponse.ok(tenantService.listTenants());
    }

    @PostMapping("/tenants")
    public ApiResponse<TenantService.Tenant> createTenant(
            @RequestBody TenantService.Tenant tenant,
            HttpServletRequest request) {
        User operator = requireAdmin(request);
        if (operator == null) return lastError();

        TenantService.Tenant created = tenantService.createTenant(tenant);
        auditLog.logSuccess(getOperator(request), "TENANT_CREATE",
                created.getTenantId() + "(" + created.getDisplayName() + ")");
        return ApiResponse.ok("创建成功", created);
    }

    // v2.22: 更新租户配额和状态
    @PutMapping("/tenants/{id}")
    public ApiResponse<TenantService.Tenant> updateTenant(@PathVariable String id,
                                                           @RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        User operator = requireAdmin(request);
        if (operator == null) return lastError();
        TenantService.Tenant updated = tenantService.updateTenant(id, body);
        auditLog.logSuccess(getOperator(request), "TENANT_UPDATE", id);
        return ApiResponse.ok("已更新", updated);
    }

    @DeleteMapping("/tenants/{id}")
    public ApiResponse<String> deleteTenant(@PathVariable String id,
                                            HttpServletRequest request) {
        User operator = requireAdmin(request);
        if (operator == null) return lastError();

        boolean ok = tenantService.deleteTenant(id);
        if (ok) auditLog.logSuccess(getOperator(request), "TENANT_DELETE", id);
        return ok ? ApiResponse.ok("已删除") : ApiResponse.error(404, "租户不存在");
    }
}
