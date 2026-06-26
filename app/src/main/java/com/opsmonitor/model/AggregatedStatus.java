package com.opsmonitor.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 系统聚合状态
 * 由 Prometheus + Docker 多数据源聚合而成
 */
@Data
@Builder
public class AggregatedStatus {

    /** 基础设施状态 */
    private String docker;
    private String prometheus;
    private String grafana;

    /** 主机资源（来自 node_exporter） */
    private double cpuUsage;
    private double memoryUsage;
    private double diskUsage;

    /** 容器统计 */
    private int containerTotal;
    private int containerRunning;

    /** 各 Job/Exporter 状态 (job名 -> "UP"/"DOWN") */
    private Map<String, String> services;

    /**
     * 按服务器分组的服务状态
     * serverId -> { exporterType -> "UP"/"DOWN" }
     */
    private Map<String, Map<String, String>> servicesByServer;

    /** 数据时效性 */
    private long timestamp;
}