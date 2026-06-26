package com.opsmonitor.controller;

import com.opsmonitor.config.ApiSafetyFilter;
import com.opsmonitor.config.InputValidator;
import com.opsmonitor.docker.DockerService;
import com.opsmonitor.model.*;
import com.opsmonitor.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Docker 容器管理 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser/getOperator 私有方法（由基类提供）
 *   - 删除 checkOpsPermission/checkAdminPermission 私有辅助方法（由基类 requireOps/requireAdmin 替代）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   改造前：151 行 → 改造后：118 行（节省 33 行）
 */
@Slf4j
@RestController
@RequestMapping("/api/containers")
@RequiredArgsConstructor
public class ContainerController extends BaseController {

    private final DockerService   dockerService;
    private final ApiSafetyFilter safetyFilter;
    private final AuditLogService auditLog;

    @GetMapping
    public ApiResponse<PageResult<ContainerInfo>> listContainers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ContainerInfo> all = dockerService.listContainers(true, status, name);
        return ApiResponse.ok(PageResult.of(all, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ContainerInfo> getContainer(@PathVariable String id) {
        InputValidator.validateContainerId(id);
        return ApiResponse.ok(dockerService.getContainer(id));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<String> startContainer(@PathVariable String id,
                                              HttpServletRequest request) {
        // OPTIMIZED: checkOpsPermission() → requireOps() + lastError()
        User op = requireOps(request);
        if (op == null) return lastError();

        InputValidator.validateContainerId(id);
        dockerService.startContainer(id);
        auditLog.logSuccess(getOperator(request), "CONTAINER_START", id);
        return ApiResponse.ok("容器已启动");
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<String> stopContainer(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int timeout,
            HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();

        InputValidator.validateContainerId(id);
        dockerService.stopContainer(id, timeout);
        auditLog.logSuccess(getOperator(request), "CONTAINER_STOP", id);
        return ApiResponse.ok("容器已停止");
    }

    @PostMapping("/{id}/restart")
    public ApiResponse<String> restartContainer(@PathVariable String id,
                                                HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();

        InputValidator.validateContainerId(id);
        dockerService.restartContainer(id);
        auditLog.logSuccess(getOperator(request), "CONTAINER_RESTART", id);
        return ApiResponse.ok("容器已重启");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> removeContainer(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest request) {
        // OPTIMIZED: checkAdminPermission() → requireAdmin() + lastError()
        User op = requireAdmin(request);
        if (op == null) return lastError();

        InputValidator.validateContainerId(id);
        dockerService.removeContainer(id, force);
        auditLog.logSuccess(getOperator(request), "CONTAINER_REMOVE", id);
        return ApiResponse.ok("容器已删除");
    }

    @GetMapping("/{id}/logs")
    public ApiResponse<ContainerLogResponse> getLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "200") int tail,
            @RequestParam(defaultValue = "true") boolean stdout,
            @RequestParam(defaultValue = "true") boolean stderr,
            @RequestParam(defaultValue = "true") boolean timestamps) {
        InputValidator.validateContainerId(id);
        int safeTail = safetyFilter.clampLogTail(tail);
        return ApiResponse.ok(dockerService.getLogs(id, safeTail, stdout, stderr, timestamps));
    }

    @GetMapping("/{id}/stats")
    public ApiResponse<ContainerStats> getStats(@PathVariable String id) {
        InputValidator.validateContainerId(id);
        return ApiResponse.ok(dockerService.getStats(id));
    }
}
