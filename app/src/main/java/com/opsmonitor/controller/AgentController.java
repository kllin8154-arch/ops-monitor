package com.opsmonitor.controller;

import com.opsmonitor.config.ApiSafetyFilter;
import com.opsmonitor.config.InputValidator;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.MonitorAgent;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AgentRegistryService;
import com.opsmonitor.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 管理 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getOperator() 私有方法（由基类提供）
 *   - 认证样板从 3 行 → 2 行（requireOps + lastError）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   改造前：81 行 → 改造后：64 行（节省 17 行）
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController extends BaseController {

    private final AgentRegistryService agentRegistry;
    private final AuditLogService      auditLog;
    private final ApiSafetyFilter      safetyFilter;
    // OPTIMIZED: authService 由 BaseController 注入，此处删除

    @PostMapping("/register")
    public ApiResponse<MonitorAgent> register(@RequestBody MonitorAgent agent,
                                              HttpServletRequest request) {
        // OPTIMIZED: 3 行样板 → 2 行
        User op = requireOps(request);
        if (op == null) return lastError();

        safetyFilter.checkAgentLimit(agentRegistry.listAgents().size());
        if (agent.getAgentId() != null)
            agent.setAgentId(InputValidator.sanitize(agent.getAgentId()));
        MonitorAgent registered = agentRegistry.register(agent);
        auditLog.logSuccess(getOperator(request), "AGENT_REGISTER",
                registered.getAgentId() + "@" + registered.getIp());
        return ApiResponse.ok("Agent 注册成功", registered);
    }

    @PostMapping("/{id}/heartbeat")
    public ApiResponse<String> heartbeat(@PathVariable String id) {
        boolean ok = agentRegistry.heartbeat(id);
        return ok ? ApiResponse.ok("heartbeat OK")
                : ApiResponse.error(404, "Agent 不存在: " + id);
    }

    @GetMapping
    public ApiResponse<List<MonitorAgent>> list() {
        return ApiResponse.ok(agentRegistry.listAgents());
    }

    @GetMapping("/{id}")
    public ApiResponse<MonitorAgent> get(@PathVariable String id) {
        MonitorAgent agent = agentRegistry.getAgent(id);
        return agent != null ? ApiResponse.ok(agent)
                : ApiResponse.error(404, "Agent 不存在: " + id);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> unregister(@PathVariable String id,
                                          HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();

        boolean ok = agentRegistry.unregister(id);
        if (ok) auditLog.logSuccess(getOperator(request), "AGENT_UNREGISTER", id);
        return ok ? ApiResponse.ok("Agent 已注销")
                : ApiResponse.error(404, "Agent 不存在: " + id);
    }
}
