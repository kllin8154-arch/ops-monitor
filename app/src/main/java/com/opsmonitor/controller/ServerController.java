package com.opsmonitor.controller;

import com.opsmonitor.config.InputValidator;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.ServerNode;
import com.opsmonitor.model.User;
import com.opsmonitor.service.AuditLogService;
import com.opsmonitor.service.ServerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务器节点管理 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser/getOperator 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板从 3 行 → 2 行
 *   改造前：109 行 → 改造后：91 行（节省 18 行）
 */
@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
public class ServerController extends BaseController {

    private final ServerService   serverService;
    private final AuditLogService auditLog;
    // OPTIMIZED: authService 由 BaseController 注入

    @GetMapping
    public ApiResponse<List<ServerNode>> list() {
        return ApiResponse.ok(serverService.listServers());
    }

    @GetMapping("/{id}")
    public ApiResponse<ServerNode> get(@PathVariable String id) {
        return ApiResponse.ok(serverService.getServer(id));
    }

    @PostMapping
    public ApiResponse<ServerNode> add(@RequestBody ServerNode node,
                                       HttpServletRequest request) {
        // OPTIMIZED: 3 行样板 → 2 行
        User op = requireOps(request);
        if (op == null) return lastError();

        if (node.getName()        != null) node.setName(InputValidator.sanitize(node.getName()));
        if (node.getHost()        != null) node.setHost(InputValidator.sanitize(node.getHost()));
        if (node.getDescription() != null) node.setDescription(InputValidator.sanitize(node.getDescription()));

        if (node.getHost() != null && !node.getHost().isBlank()) {
            try {
                InputValidator.validateServerHost(node.getHost());
            } catch (IllegalArgumentException e) {
                return ApiResponse.error(400, "服务器地址无效: " + e.getMessage());
            }
        } else {
            return ApiResponse.error(400, "服务器 IP 地址不能为空");
        }
        if (node.getName() == null || node.getName().isBlank()) {
            return ApiResponse.error(400, "服务器名称不能为空");
        }

        try {
            ServerNode added = serverService.addServer(node);
            auditLog.logSuccess(getOperator(request), "SERVER_ADD",
                    added.getId() + "(" + added.getName() + ")");
            return ApiResponse.ok("服务器已添加", added);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<ServerNode> update(@PathVariable String id,
                                          @RequestBody ServerNode node,
                                          HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();

        if (node.getName()        != null) node.setName(InputValidator.sanitize(node.getName()));
        if (node.getHost()        != null) node.setHost(InputValidator.sanitize(node.getHost()));
        if (node.getDescription() != null) node.setDescription(InputValidator.sanitize(node.getDescription()));

        if (node.getHost() != null && !node.getHost().isBlank()) {
            try {
                InputValidator.validateServerHost(node.getHost());
            } catch (IllegalArgumentException e) {
                return ApiResponse.error(400, "服务器地址无效: " + e.getMessage());
            }
        }
        if ("local".equals(id) && node.getHost() != null) {
            return ApiResponse.error(400, "不允许修改本机节点的 IP 地址");
        }

        try {
            ServerNode updated = serverService.updateServer(id, node);
            auditLog.logSuccess(getOperator(request), "SERVER_UPDATE", id);
            return ApiResponse.ok("服务器已更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable String id,
                                      HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();

        serverService.deleteServer(id);
        auditLog.logSuccess(getOperator(request), "SERVER_DELETE", id);
        return ApiResponse.ok("服务器已删除: " + id);
    }
}
