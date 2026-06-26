package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.model.User;
import com.opsmonitor.monitor.ExporterManager;
import com.opsmonitor.platform.DisplayNameService;
import com.opsmonitor.platform.ResourceReconciler;
import com.opsmonitor.service.AgentRegistryService;
import com.opsmonitor.service.SystemAuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 显示名称 + 系统概览 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 内联认证改为 requireOps
 *   改造前：109 行 → 改造后：96 行（节省 13 行）
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DisplayNameController extends BaseController {

    private final DisplayNameService   displayNameService;
    private final ResourceReconciler   reconciler;
    private final ExporterManager      exporterManager;
    private final AgentRegistryService agentRegistry;
    private final SystemAuditService   auditService;

    @PutMapping("/display-name/{kind}/{name}")
    public ApiResponse<String> setDisplayName(
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        // OPTIMIZED: 内联 3 行样板 → 2 行
        User u = requireOps(request);
        if (u == null) return lastError();

        String displayName = body.get("displayName");
        if (displayName == null || displayName.isBlank()) {
            return ApiResponse.error(400, "displayName 不能为空");
        }

        displayNameService.setDisplayName(kind, name, displayName);
        reconciler.requestReconcile();
        return ApiResponse.ok("已更新: " + kind + "/" + name + " → " + displayName);
    }

    @GetMapping("/display-name")
    public ApiResponse<Map<String, String>> getAllDisplayNames() {
        return ApiResponse.ok(displayNameService.getAll());
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> data = new LinkedHashMap<>();

        var exporters = exporterManager.listExporters();
        long exporterUp = exporters.stream()
                .filter(e -> "running".equals(e.getState()) || "remote".equals(e.getState()))
                .count();
        data.put("exporterTotal", exporters.size());
        data.put("exporterUp", exporterUp);

        var agents = agentRegistry.listAgents();
        long agentOnline = agents.stream()
                .filter(a -> "ONLINE".equals(a.getStatus()))
                .count();
        data.put("agentTotal", agents.size());
        data.put("agentOnline", agentOnline);

        try {
            var audit = auditService.runAudit();
            data.put("auditScore", audit.getHealthScore());
            data.put("auditGrade", audit.getGrade());
            data.put("auditIssues", audit.getIssues().size());
        } catch (Exception e) {
            data.put("auditScore", 0);
            data.put("auditGrade", "N/A");
        }

        return ApiResponse.ok(data);
    }
}
