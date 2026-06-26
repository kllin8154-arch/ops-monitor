package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.OpsTask;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuditLogService;
import com.opsmonitor.service.TaskSchedulerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任务编排 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser() 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板从 3 行 → 2 行
 *   改造前：59 行 → 改造后：46 行（节省 13 行）
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController extends BaseController {
    private final TaskSchedulerService taskService;
    private final AuditLogService      auditLog;

    @PostMapping
    public ApiResponse<OpsTask> submit(@RequestBody OpsTask task,
                                       HttpServletRequest request) {
        // OPTIMIZED: 3 行样板 → 2 行
        User u = requireOps(request);
        if (u == null) return lastError();

        OpsTask created = taskService.submitTask(task);
        auditLog.logSuccess(u.getUsername(), "TASK_SUBMIT",
                created.getId() + ":" + created.getType());
        return ApiResponse.ok("任务已提交", created);
    }

    @GetMapping
    public ApiResponse<List<OpsTask>> list(@RequestParam(defaultValue = "50") int limit,
                                           HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();
        return ApiResponse.ok(taskService.listTasks(Math.min(limit, 500)));
    }

    @GetMapping("/{id}")
    public ApiResponse<OpsTask> get(@PathVariable String id, HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();
        return ApiResponse.ok(taskService.getTask(id));
    }
}
