package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 远程监控 Agent 节点 (10A-1)
 *
 * Agent 部署在远程服务器上，负责：
 * - 本地 Exporter 管理
 * - 服务自动发现
 * - 指标采集 + Remote Write
 *
 * 注册后由 Control Plane 统一管理
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorAgent {

    /** Agent 唯一 ID (hostname-based) */
    private String agentId;

    /** 主机名 */
    private String hostname;

    /** IP 地址 */
    private String ip;

    /** 操作系统 */
    private String os;

    /** CPU 核数 */
    private int cpuCores;

    /** 内存大小 (MB) */
    private long memoryMb;

    /** Agent 版本 */
    private String agentVersion;

    /** Agent 状态: ONLINE / OFFLINE / UNKNOWN */
    @Builder.Default
    private String status = "UNKNOWN";

    /** 已部署的 Exporter 类型列表 */
    private List<String> exporterTypes;

    /** 自动发现的服务列表 */
    private List<String> discoveredServices;

    /** 自定义标签 */
    private Map<String, String> labels;

    /** 注册时间 */
    @Builder.Default
    private long registeredAt = System.currentTimeMillis();

    /** 最后心跳时间 */
    @Builder.Default
    private long lastHeartbeat = System.currentTimeMillis();
}