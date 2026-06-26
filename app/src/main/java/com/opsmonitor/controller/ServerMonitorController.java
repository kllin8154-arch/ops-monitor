package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuditLogService;
import com.opsmonitor.service.GrafanaSyncService;
import com.opsmonitor.service.ServerDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 服务器监控扩展 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getOperator() 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板从 3 行 → 2 行
 *   - GET /port-scan 补加 VIEWER 认证（安全修复：防内网拓扑泄露）
 *   改造前：104 行 → 改造后：82 行（节省 22 行）
 */
@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
public class ServerMonitorController extends BaseController {

    private final ServerDashboardService dashboardService;
    private final GrafanaSyncService     grafanaSyncService;
    private final AuditLogService        auditLog;

    // OPTIMIZED: 安全修复 — 端口扫描结果含内网拓扑信息，需要登录才能查看
    @GetMapping("/port-scan")
    public ApiResponse<List<Map<String, Object>>> getAllPortScans(HttpServletRequest request) {
        User op = requireViewer(request);
        if (op == null) return lastError();

        List<Map<String, Object>> results = dashboardService.getAllScanResults()
                .stream()
                .map(ServerDashboardService.PortScanResult::toMap)
                .collect(Collectors.toList());
        return ApiResponse.ok(results);
    }

    // OPTIMIZED: 安全修复 — 同上，GET 单台也需要认证
    @GetMapping("/{id}/port-scan")
    public ApiResponse<Map<String, Object>> getPortScan(@PathVariable String id,
                                                         HttpServletRequest request) {
        User op = requireViewer(request);
        if (op == null) return lastError();

        return dashboardService.getScanResult(id)
                .map(r -> ApiResponse.ok(r.toMap()))
                .orElse(ApiResponse.error(404, "暂无扫描结果，请先触发扫描"));
    }

    @PostMapping("/{id}/port-scan")
    public ApiResponse<String> triggerScan(@PathVariable String id,
                                           HttpServletRequest request) {
        // OPTIMIZED: 3 行样板 → 2 行
        User op = requireOps(request);
        if (op == null) return lastError();

        dashboardService.triggerScan(id);
        auditLog.logSuccess(op.getUsername(), "PORT_SCAN", id);
        return ApiResponse.ok("端口扫描已触发（异步），请稍后刷新结果");
    }

    @PostMapping("/regenerate-dashboards")
    public ApiResponse<String> regenerateDashboards(HttpServletRequest request) {
        User op = requireAdmin(request);
        if (op == null) return lastError();

        dashboardService.generateAllServerDashboards();
        auditLog.logSuccess(op.getUsername(), "REGENERATE_DASHBOARDS", "all");
        return ApiResponse.ok("所有服务器仪表盘已重新生成");
    }

    @PostMapping("/sync-grafana")
    public ApiResponse<String> syncGrafana(HttpServletRequest request) {
        User op = requireAdmin(request);
        if (op == null) return lastError();

        auditLog.logSuccess(op.getUsername(), "GRAFANA_SYNC", "manual-full-sync");
        grafanaSyncService.syncAll();
        return ApiResponse.ok("Grafana 全量同步已触发（异步执行）：重建所有仪表盘 + provisioning reload");
    }
}
