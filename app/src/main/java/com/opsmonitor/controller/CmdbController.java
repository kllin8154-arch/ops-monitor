package com.opsmonitor.controller;

import com.opsmonitor.config.InputValidator;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.Asset;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuditLogService;
import com.opsmonitor.service.CmdbService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CMDB 资产管理 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser/getOperator 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板从 3 行 → 2 行
 *   - sanitizeAsset() 统一清理 7 个字段（原来只清理 hostname）
 *   改造前：103 行 → 改造后：80 行（节省 23 行）
 */
@RestController
@RequestMapping("/api/cmdb/assets")
@RequiredArgsConstructor
public class CmdbController extends BaseController {

    private final CmdbService     cmdbService;
    private final AuditLogService auditLog;

    @GetMapping
    public ApiResponse<List<Asset>> list() {
        return ApiResponse.ok(cmdbService.listAssets());
    }

    @GetMapping("/{id}")
    public ApiResponse<Asset> get(@PathVariable String id) {
        return ApiResponse.ok(cmdbService.getAsset(id));
    }

    @GetMapping("/by-project/{project}")
    public ApiResponse<List<Asset>> byProject(@PathVariable String project) {
        return ApiResponse.ok(cmdbService.listByProject(project));
    }

    @GetMapping("/by-env/{env}")
    public ApiResponse<List<Asset>> byEnv(@PathVariable String env) {
        return ApiResponse.ok(cmdbService.listByEnvironment(env));
    }

    @GetMapping("/stats/by-project")
    public ApiResponse<Map<String, Long>> statsByProject() {
        return ApiResponse.ok(cmdbService.countByProject());
    }

    @GetMapping("/stats/by-env")
    public ApiResponse<Map<String, Long>> statsByEnv() {
        return ApiResponse.ok(cmdbService.countByEnvironment());
    }

    @PostMapping
    public ApiResponse<Asset> add(@RequestBody Asset asset, HttpServletRequest request) {
        User op = requirePermission(request, "cmdb:manage");
        if (op == null) return lastError();

        sanitizeAsset(asset);
        Asset added = cmdbService.addAsset(asset);
        auditLog.logSuccess(getOperator(request), "CMDB_ADD",
                added.getId() + "(" + added.getHostname() + ")");
        return ApiResponse.ok("资产已添加", added);
    }

    @PutMapping("/{id}")
    public ApiResponse<Asset> update(@PathVariable String id,
                                     @RequestBody Asset asset,
                                     HttpServletRequest request) {
        User op = requirePermission(request, "cmdb:manage");
        if (op == null) return lastError();

        sanitizeAsset(asset);
        Asset updated = cmdbService.updateAsset(id, asset);
        auditLog.logSuccess(getOperator(request), "CMDB_UPDATE", id);
        return ApiResponse.ok("资产已更新", updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable String id, HttpServletRequest request) {
        User op = requirePermission(request, "cmdb:manage");
        if (op == null) return lastError();

        cmdbService.deleteAsset(id);
        auditLog.logSuccess(getOperator(request), "CMDB_DELETE", id);
        return ApiResponse.ok("资产已删除");
    }

    // OPTIMIZED: 统一清理 7 个字段（原来只清理 hostname）
    private void sanitizeAsset(Asset asset) {
        if (asset == null) return;
        if (asset.getHostname()    != null) asset.setHostname(InputValidator.sanitize(asset.getHostname()));
        if (asset.getIp()          != null) asset.setIp(InputValidator.sanitize(asset.getIp()));
        if (asset.getOs()          != null) asset.setOs(InputValidator.sanitize(asset.getOs()));
        if (asset.getEnvironment() != null) asset.setEnvironment(InputValidator.sanitize(asset.getEnvironment()));
        if (asset.getProject()     != null) asset.setProject(InputValidator.sanitize(asset.getProject()));
        if (asset.getOwner()       != null) asset.setOwner(InputValidator.sanitize(asset.getOwner()));
        if (asset.getDescription() != null) asset.setDescription(InputValidator.sanitize(asset.getDescription()));
    }
}
