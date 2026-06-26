package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.monitor.ExporterDeployService;
import com.opsmonitor.service.ServiceTopologyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 服务拓扑 API
 *
 * 端点：
 * - GET /api/topology              → 服务拓扑数据
 * - GET /api/deploy/command/{type}  → 生成 docker run 命令（兼容旧接口）
 *
 * 注意：/api/deploy/examples 和 /api/deploy/docker|binary|systemd
 * 由 MonitorApiController 统一提供，此处不再重复。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TopologyController {

    private final ServiceTopologyService topologyService;
    private final ExporterDeployService deployService;

    /**
     * 获取服务拓扑数据
     */
    @GetMapping("/topology")
    public ApiResponse<ServiceTopologyService.TopologyData> getTopology() {
        ServiceTopologyService.TopologyData topology = topologyService.buildTopology();
        return ApiResponse.ok("服务拓扑", topology);
    }

    /**
     * 生成指定类型的 docker run 命令（兼容旧接口）
     */
    @GetMapping("/deploy/command/{type}")
    public ApiResponse<ExporterDeployService.DeployScript> getDeployCommand(
            @PathVariable String type,
            @RequestParam(defaultValue = "0") int port,
            @RequestParam(required = false) String target) {

        ExporterDeployService.DeployScript script = deployService.generateDockerScript(
                type, port > 0 ? port : 0, target);
        return ApiResponse.ok("部署命令已生成", script);
    }
}