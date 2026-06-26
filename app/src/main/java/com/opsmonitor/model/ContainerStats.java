package com.opsmonitor.model;

import lombok.Builder;
import lombok.Data;

/**
 * 容器资源统计模型
 *
 * CPU 计算方式:
 *   cpuDelta = container_cpu_usage_total - prev_container_cpu_usage_total
 *   systemDelta = system_cpu_usage - prev_system_cpu_usage
 *   cpuPercent = (cpuDelta / systemDelta) * numCpus * 100.0
 *
 * 内存计算方式:
 *   memoryUsed = usage - cache (若有 stats.inactive_file)
 *   memoryPercent = (memoryUsed / memoryLimit) * 100.0
 */
@Data
@Builder
public class ContainerStats {

    /** 容器ID */
    private String containerId;

    /** 容器名称 */
    private String containerName;

    /** CPU 使用百分比 */
    private Double cpuPercent;

    /** 内存使用量（字节） */
    private Long memoryUsed;

    /** 内存限制（字节） */
    private Long memoryLimit;

    /** 内存使用百分比 */
    private Double memoryPercent;

    /** 内存使用量（可读格式） */
    private String memoryUsedFormatted;

    /** 内存限制（可读格式） */
    private String memoryLimitFormatted;

    /** 网络接收（字节） */
    private Long networkRx;

    /** 网络发送（字节） */
    private Long networkTx;

    /** 网络接收（可读格式） */
    private String networkRxFormatted;

    /** 网络发送（可读格式） */
    private String networkTxFormatted;

    /** 磁盘读取（字节） */
    private Long blockRead;

    /** 磁盘写入（字节） */
    private Long blockWrite;

    /** PIDs 数量 */
    private Long pids;
}
