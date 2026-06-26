package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 心跳请求 (10B-4)
 *
 * 扩展字段：
 * - metricsRate: 每秒写入 metrics 数量
 * - exporterCount: 已部署 Exporter 数量
 * - uptimeSeconds: Agent 运行时长
 * - loadAvg: 系统负载
 * - exporterTypes: 已部署的 Exporter 类型列表
 * - remoteWriteStatus: Remote Write 连接状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentHeartbeat {

    private String agentId;
    private double metricsRate;
    private int exporterCount;
    private long uptimeSeconds;
    private double loadAvg;
    private List<String> exporterTypes;
    private String remoteWriteStatus; // OK / FAIL / UNKNOWN
    private double cpuUsagePercent;
    private long memoryUsedMb;
}