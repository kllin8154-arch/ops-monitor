package com.opsmonitor.platform.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 平台资源模型定义 (10D-1)
 *
 * 7 种资源类型，形成层级：
 *   Tenant → Project → Service → Instance
 *                                   ↕
 *                              Agent ↔ Exporter
 *                              AlertRule / Dashboard
 */
public class ResourceModels {

    @Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class ProjectResource extends ManagedResource {
        private String description;
        private String owner;
        private List<String> serviceNames;

        { setKind("Project"); }
    }

    @Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class ServiceResource extends ManagedResource {
        private String projectName;
        private String serviceType; // web / api / database / cache / middleware
        private List<String> instanceNames;
        private String slaTarget; // 99.9%

        { setKind("Service"); }
    }

    @Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class InstanceResource extends ManagedResource {
        private String serviceName;
        private String projectName;
        private String serverHost;
        private int port;
        private String exporterId;
        private String agentId;

        { setKind("Instance"); }
    }

    @Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class AgentResource extends ManagedResource {
        private String hostname;
        private String ip;
        private String os;
        private int cpuCores;
        private long memoryMb;
        private String agentVersion;
        private String protocolVersion;
        private boolean remoteWriteEnabled;
        private List<String> exporterTypes;
        private long lastHeartbeat;

        { setKind("Agent"); }
    }

    @Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class ExporterResource extends ManagedResource {
        private String type; // redis / mysql / nginx ...
        private String serverId;
        private String targetAddress;
        private int metricsPort;
        private String projectName;
        private String serviceName;
        private boolean managedByDocker;

        { setKind("Exporter"); }
    }

    @Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class AlertRuleResource extends ManagedResource {
        private String group; // infrastructure / exporters / platform
        private String expr;
        private String duration;
        private String severity;
        private String summary;
        private String description;
        private boolean enabled;

        { setKind("AlertRule"); }
    }

    @Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class DashboardResource extends ManagedResource {
        private String uid;
        private String title;
        private String layer; // global / agent / project / service / exporter
        private List<String> variables;

        { setKind("Dashboard"); }
    }
}