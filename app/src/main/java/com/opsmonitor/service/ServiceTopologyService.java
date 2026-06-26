package com.opsmonitor.service;

import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.model.MonitorAgent;
import com.opsmonitor.model.ServerNode;
import com.opsmonitor.monitor.ExporterManager;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 服务拓扑引擎 V2 (10B-5)
 *
 * 4 层拓扑结构：
 *   Global
 *     └─ Project
 *          └─ Service
 *               ├─ Instance (Exporter)
 *               └─ Agent
 *
 * 支持 Grafana 拓扑面板 + 前端可视化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTopologyService {

    private final ServerService serverService;
    private final ExporterManager exporterManager;
    private final AgentRegistryService agentRegistry;

    /**
     * 构建完整拓扑
     */
    public TopologyData buildTopology() {
        List<ExporterInstance> exporters = exporterManager.listExporters();
        List<ServerNode> servers = serverService.listServers();
        List<MonitorAgent> agents = agentRegistry.listAgents();

        // 按项目分组
        Map<String, List<ExporterInstance>> byProject = exporters.stream()
                .filter(e -> e.getProject() != null && !e.getProject().isBlank())
                .collect(Collectors.groupingBy(ExporterInstance::getProject));

        // 构建项目拓扑
        List<ProjectNode> projectNodes = new ArrayList<>();
        for (Map.Entry<String, List<ExporterInstance>> entry : byProject.entrySet()) {
            String project = entry.getKey();
            List<ExporterInstance> projExporters = entry.getValue();

            // 按服务分组
            Map<String, List<ExporterInstance>> byService = projExporters.stream()
                    .filter(e -> e.getService() != null && !e.getService().isBlank())
                    .collect(Collectors.groupingBy(ExporterInstance::getService));

            List<ServiceNode> serviceNodes = new ArrayList<>();
            for (Map.Entry<String, List<ExporterInstance>> svcEntry : byService.entrySet()) {
                List<InstanceNode> instances = svcEntry.getValue().stream()
                        .map(e -> InstanceNode.builder()
                                .exporterId(e.getId())
                                .type(e.getType())
                                .scrapeTarget(e.getScrapeTarget())
                                .state(e.getState())
                                .serverId(e.getServerId())
                                .build())
                        .toList();

                serviceNodes.add(ServiceNode.builder()
                        .service(svcEntry.getKey())
                        .instanceCount(instances.size())
                        .instances(instances)
                        .build());
            }

            // 关联的 Agent
            Set<String> projServerIds = projExporters.stream()
                    .map(e -> e.getServerId() != null ? e.getServerId() : "local")
                    .collect(Collectors.toSet());
            List<AgentNode> agentNodes = agents.stream()
                    .filter(a -> projServerIds.contains(a.getAgentId()) || projServerIds.contains(a.getIp()))
                    .map(a -> AgentNode.builder()
                            .agentId(a.getAgentId())
                            .ip(a.getIp())
                            .status(a.getStatus())
                            .exporterCount(a.getExporterTypes() != null ? a.getExporterTypes().size() : 0)
                            .build())
                    .toList();

            projectNodes.add(ProjectNode.builder()
                    .project(project)
                    .serviceCount(serviceNodes.size())
                    .exporterCount(projExporters.size())
                    .agentCount(agentNodes.size())
                    .services(serviceNodes)
                    .agents(agentNodes)
                    .build());
        }

        // 类型统计
        Map<String, Integer> typeCounts = exporters.stream()
                .collect(Collectors.groupingBy(ExporterInstance::getType,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        return TopologyData.builder()
                .totalServers(servers.size())
                .totalExporters(exporters.size())
                .totalAgents(agents.size())
                .totalProjects(byProject.size())
                .onlineAgents((int) agents.stream().filter(a -> "ONLINE".equals(a.getStatus())).count())
                .projects(projectNodes)
                .exportersByType(typeCounts)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ==================== 数据模型 ====================

    @Data @Builder
    public static class TopologyData {
        private int totalServers;
        private int totalExporters;
        private int totalAgents;
        private int onlineAgents;
        private int totalProjects;
        private List<ProjectNode> projects;
        private Map<String, Integer> exportersByType;
        private long timestamp;
    }

    @Data @Builder
    public static class ProjectNode {
        private String project;
        private int serviceCount;
        private int exporterCount;
        private int agentCount;
        private List<ServiceNode> services;
        private List<AgentNode> agents;
    }

    @Data @Builder
    public static class ServiceNode {
        private String service;
        private int instanceCount;
        private List<InstanceNode> instances;
    }

    @Data @Builder
    public static class InstanceNode {
        private String exporterId;
        private String type;
        private String scrapeTarget;
        private String state;
        private String serverId;
    }

    @Data @Builder
    public static class AgentNode {
        private String agentId;
        private String ip;
        private String status;
        private int exporterCount;
    }
}