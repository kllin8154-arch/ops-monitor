package com.opsmonitor.controller;

import com.opsmonitor.config.ApiSafetyFilter;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.platform.AlertCenterService;
import com.opsmonitor.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警中心 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser() 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板统一使用 requirePermission + lastError
 *   改造前：129 行 → 改造后：108 行（节省 21 行）
 */
@RestController
@RequestMapping("/api/alert-center")
@RequiredArgsConstructor
public class AlertCenterController extends BaseController {

    private final AlertCenterService alertCenter;
    private final AuditLogService    auditLog;
    private final ApiSafetyFilter    safetyFilter;

    @GetMapping("/active")
    public ApiResponse<List<AlertCenterService.Alert>> activeAlerts(
            @RequestParam(required = false) String tenant) {
        List<AlertCenterService.Alert> alerts = (tenant != null)
                ? alertCenter.getActiveAlertsByTenant(tenant)
                : alertCenter.getActiveAlerts();
        return ApiResponse.ok(alerts);
    }

    @GetMapping("/history")
    public ApiResponse<List<AlertCenterService.Alert>> history(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(alertCenter.getAlertHistory(Math.min(limit, 500)));
    }

    @PostMapping("/{id}/ack")
    public ApiResponse<String> ack(@PathVariable String id, HttpServletRequest request) {
        // OPTIMIZED: N19 修复保留（操作者从 Token 中获取，不接受客户端传参）
        User operator = requirePermission(request, "alerts:manage");
        if (operator == null) return lastError();

        boolean ok = alertCenter.ackAlert(id, operator.getUsername());
        if (ok) {
            auditLog.logSuccess(operator.getUsername(), "ALERT_ACK", id);
            return ApiResponse.ok("告警已确认");
        }
        return ApiResponse.error(404, "告警不存在");
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> result = Map.of(
                "bySeverity",  alertCenter.statsBySeverity(),
                "byAlertName", alertCenter.statsByAlertName(),
                "activeCount", alertCenter.getActiveAlerts().size()
        );
        return ApiResponse.ok(result);
    }

    @PostMapping("/silences")
    public ApiResponse<AlertCenterService.SilenceRule> createSilence(
            @RequestBody AlertCenterService.SilenceRule rule,
            HttpServletRequest request) {
        User operator = requirePermission(request, "alerts:manage");
        if (operator == null) return lastError();

        // N23修复：XSS 清理静默规则字段
        if (rule.getMatchAlertName()    != null) rule.setMatchAlertName(safetyFilter.sanitize(rule.getMatchAlertName()));
        if (rule.getMatchServerName()   != null) rule.setMatchServerName(safetyFilter.sanitize(rule.getMatchServerName()));
        if (rule.getMatchExporterType() != null) rule.setMatchExporterType(safetyFilter.sanitize(rule.getMatchExporterType()));

        AlertCenterService.SilenceRule created = alertCenter.createSilence(rule);
        auditLog.logSuccess(operator.getUsername(), "SILENCE_CREATE", created.getId());
        return ApiResponse.ok("静默已创建", created);
    }

    @GetMapping("/silences")
    public ApiResponse<List<AlertCenterService.SilenceRule>> listSilences() {
        return ApiResponse.ok(alertCenter.listSilences());
    }

    @DeleteMapping("/silences/{id}")
    public ApiResponse<String> deleteSilence(@PathVariable String id,
                                             HttpServletRequest request) {
        User operator = requirePermission(request, "alerts:manage");
        if (operator == null) return lastError();

        boolean ok = alertCenter.deleteSilence(id);
        if (ok) {
            auditLog.logSuccess(operator.getUsername(), "SILENCE_DELETE", id);
            return ApiResponse.ok("已删除");
        }
        return ApiResponse.error(404, "不存在");
    }
}
