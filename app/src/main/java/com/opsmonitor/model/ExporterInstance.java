package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Exporter 运行实例
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExporterInstance {

    private String id;
    private String type;
    private String displayName;

    /** 所属服务器节点 ID */
    private String serverId;

    /**
     * 所属服务器名称（冗余字段，用于 Prometheus label server_name）
     * 修复：PrometheusTargetWriter 原来错误地将 displayName（Exporter类型名）写入 server_name label
     */
    private String serverName;

    /** 目标服务地址 */
    private String targetAddress;

    /** 项目名称 */
    private String project;

    /** 服务名称 */
    private String service;

    /** 环境标签 */
    private String environment;

    private String containerId;
    private String containerName;
    private String state;
    private int metricsPort;
    private String jobName;
    private String scrapeTarget;
    private Map<String, String> env;
    private boolean registeredInPrometheus;

    /**
     * 是否由 Docker 管理
     * true  = 本机模式（可 start/stop/restart/logs）
     * false = 远程模式（仅 Prometheus 注册，不可操作容器）
     */
    @Builder.Default
    private boolean managedByDocker = true;

    /**
     * v2.13-A: Agent 联级状态标记
     * null  = 无关联 Agent 或 Agent 在线
     * "AGENT_OFFLINE" = 关联的 Agent 心跳超时，Exporter 状态不可确认
     */
    private String agentStatus;

    /** v2.31: Prometheus 采集健康状态（UP/DOWN/PENDING/UNKNOWN） */
    private String healthStatus;

    @Builder.Default
    private long createdAt = System.currentTimeMillis();
}