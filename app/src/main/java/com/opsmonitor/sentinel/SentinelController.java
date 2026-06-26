package com.opsmonitor.sentinel;

import com.opsmonitor.controller.BaseController;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuditLogService;
import com.opsmonitor.service.ServerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sentinel 统一 API 控制器
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser() 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板从 3 行 → 2 行（贯穿 8 个需要权限的接口）
 *   改造前：285 行 → 改造后：252 行（节省 33 行）
 *
 * 注意：此 Controller 位于 sentinel 包，通过 import 使用 BaseController。
 */
@Slf4j
@RestController
@RequestMapping("/api/sentinel")
@RequiredArgsConstructor
public class SentinelController extends BaseController {

    private final DiagnosisEngine  diagnosisEngine;
    private final IncidentService  incidentService;
    private final RunbookExecutor  runbookExecutor;
    private final ServerService    serverService;
    private final AuditLogService  auditLog;
    // OPTIMIZED: authService 由 BaseController 注入

    // ── Incident 查询 ─────────────────────────────────────────

    @GetMapping("/incidents")
    public ApiResponse<List<Incident>> listAll() {
        return ApiResponse.ok(incidentService.listAll());
    }

    @GetMapping("/incidents/open")
    public ApiResponse<List<Incident>> listOpen() {
        return ApiResponse.ok(incidentService.listOpen());
    }

    @GetMapping("/incidents/{id}")
    public ApiResponse<Incident> getIncident(@PathVariable String id) {
        return incidentService.findById(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error(404, "Incident 不存在: " + id));
    }

    // ── Incident 生命周期 ────────────────────────────────────

    @PostMapping("/incidents/{id}/investigate")
    public ApiResponse<Incident> investigate(@PathVariable String id,
                                             HttpServletRequest request) {
        // OPTIMIZED: 3 行样板 → 2 行
        User op = requireOps(request);
        if (op == null) return lastError();

        Incident updated = incidentService.investigate(id, op.getUsername());
        auditLog.logSuccess(op.getUsername(), "INCIDENT_INVESTIGATE", id);
        return ApiResponse.ok("已标记为调查中", updated);
    }

    @PostMapping("/incidents/{id}/resolve")
    public ApiResponse<Incident> resolve(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, String> body,
                                         HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();

        String rawNotes = body != null ? body.getOrDefault("notes", "") : "";
        String notes = com.opsmonitor.config.InputValidator.sanitize(rawNotes);
        Incident updated = incidentService.resolve(id, notes);
        auditLog.logSuccess(op.getUsername(), "INCIDENT_RESOLVE", id);
        return ApiResponse.ok("Incident 已解决", updated);
    }

    @DeleteMapping("/incidents/{id}")
    public ApiResponse<Incident> close(@PathVariable String id, HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();

        Incident updated = incidentService.close(id);
        auditLog.logSuccess(op.getUsername(), "INCIDENT_CLOSE", id);
        return ApiResponse.ok("Incident 已关闭", updated);
    }

    // ── 核心：人工确认后执行 Runbook ─────────────────────────

    @PostMapping("/incidents/{id}/execute")
    public ApiResponse<RunbookExecutor.RunbookExecution> executeRunbook(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> extraContext,
            HttpServletRequest request) {

        User op = requireOps(request);
        if (op == null) return lastError();

        Incident incident = incidentService.getOrThrow(id);
        if (incident.getRunbookSteps() == null || incident.getRunbookSteps().isEmpty()) {
            return ApiResponse.error(400, "该 Incident 无关联 Runbook 步骤");
        }

        Map<String, Object> context = buildContext(incident, extraContext);

        log.info("[SentinelController] 用户 {} 执行 Runbook for Incident {}", op.getUsername(), id);
        auditLog.logSuccess(op.getUsername(), "RUNBOOK_EXECUTE",
                id + "(" + incident.getFaultName() + ")");

        incidentService.investigate(id, op.getUsername());

        RunbookExecutor.RunbookExecution execution = runbookExecutor.execute(
                incident.getFaultName(),
                incident.getRunbookSteps(),
                context
        );

        incidentService.recordExecution(id, execution.getStepResults());

        return ApiResponse.ok(
                execution.isOverallSuccess() ? "Runbook 执行成功" : "Runbook 执行完成（部分步骤失败）",
                execution
        );
    }

    // ── 诊断 ─────────────────────────────────────────────────

    @PostMapping("/diagnose/{serverId}")
    public ApiResponse<List<DiagnosisEngine.DiagnosisReport>> diagnose(
            @PathVariable String serverId,
            HttpServletRequest request) {

        User op = requireOps(request);
        if (op == null) return lastError();

        String serverName = serverId;
        try { serverName = serverService.getServer(serverId).getName(); } catch (Exception ignored) {}

        log.info("[SentinelController] 用户 {} 触发诊断: {}", op.getUsername(), serverName);
        List<DiagnosisEngine.DiagnosisReport> reports = diagnosisEngine.diagnose(serverId, serverName);

        for (DiagnosisEngine.DiagnosisReport r : reports) {
            if (r.getConfidence() >= 60.0) {
                // v2.12: 传入 indicatorSnapshot 供事后分析
                incidentService.open(
                        serverId, serverName, r.getFaultId(), r.getFaultName(),
                        r.getSeverity(), r.getImpactScore(), r.getConfidence(),
                        r.getRootCause(), buildRunbookSteps(r),
                        r.getIndicatorSnapshot(), null
                );
            }
        }

        auditLog.logSuccess(op.getUsername(), "SENTINEL_DIAGNOSE", serverId);
        return ApiResponse.ok("诊断完成，匹配 " + reports.size() + " 个故障指纹", reports);
    }

    // ── 直接执行 Runbook ─────────────────────────────────────

    @PostMapping("/runbook/execute")
    public ApiResponse<RunbookExecutor.RunbookExecution> executeRunbookDirect(
            @RequestBody RunbookExecuteRequest req,
            HttpServletRequest request) {

        User op = requireOps(request);
        if (op == null) return lastError();

        if (req.getSteps() == null || req.getSteps().isEmpty())
            return ApiResponse.error(400, "steps 不能为空");

        Map<String, Object> ctx = req.getContext() != null ? req.getContext() : new HashMap<>();
        RunbookExecutor.RunbookExecution result = runbookExecutor.execute(
                req.getName() != null ? req.getName() : "direct",
                req.getSteps(), ctx);

        auditLog.logSuccess(op.getUsername(), "RUNBOOK_DIRECT_EXECUTE",
                req.getSteps().size() + " steps");
        return ApiResponse.ok(result.isOverallSuccess() ? "执行成功" : "执行失败", result);
    }

    @GetMapping("/fingerprints")
    public ApiResponse<List<DiagnosisEngine.FaultFingerprint>> getFingerprints() {
        return ApiResponse.ok(diagnosisEngine.getFingerprintLibrary());
    }

    // ── 私有方法 ─────────────────────────────────────────────

    private Map<String, Object> buildContext(Incident incident, Map<String, Object> extra) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("serverId",    incident.getServerId());
        ctx.put("serverName",  incident.getServerName());
        ctx.put("fingerprint", incident.getFingerprint());
        ctx.put("severity",    incident.getSeverity());
        ctx.put("incidentId",  incident.getId());
        try {
            String host = serverService.getServer(incident.getServerId()).getHost();
            ctx.put("serverHost", host);
        } catch (Exception ignored) {}
        if (extra != null) ctx.putAll(extra);
        return ctx;
    }

    private List<RunbookStep> buildRunbookSteps(DiagnosisEngine.DiagnosisReport r) {
        List<RunbookStep> steps = new ArrayList<>();
        steps.add(RunbookStep.builder()
                .name("诊断记录").type("LOG")
                .command("故障: " + r.getFaultName()
                        + " | 置信度: " + String.format("%.0f", r.getConfidence())
                        + "% | 根因: " + r.getRootCause())
                .failFast(false).build());
        List<String> runbook = r.getRunbook();
        if (runbook != null) {
            for (String step : runbook) {
                steps.add(RunbookStep.builder()
                        .name(step.length() > 40 ? step.substring(0, 40) : step)
                        .type("LOG").command(step).failFast(false).build());
            }
        }
        return steps;
    }

    @lombok.Data
    public static class RunbookExecuteRequest {
        private String name;
        private List<RunbookStep> steps;
        private Map<String, Object> context;
    }
}
