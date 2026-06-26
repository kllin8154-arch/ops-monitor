package com.opsmonitor.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 容器信息模型
 */
@Data
@Builder
public class ContainerInfo {

    /** 容器ID（短） */
    private String id;

    /** 容器完整ID */
    private String fullId;

    /** 容器名称 */
    private String name;

    /** 镜像名称 */
    private String image;

    /** 容器状态描述（如 Up 2 hours） */
    private String status;

    /** 容器运行状态（running / exited / created / paused） */
    private String state;

    /** 端口映射 */
    private List<PortMapping> ports;

    /** 创建时间（Unix时间戳） */
    private Long created;

    /** CPU 使用率（百分比） */
    private Double cpuUsage;

    /** 内存使用量（字节） */
    private Long memoryUsage;

    /** 内存限制（字节） */
    private Long memoryLimit;

    /** 网络名称 */
    private String networkMode;

    /** 是否为系统受保护容器 */
    private boolean protectedContainer;

    @Data
    @Builder
    public static class PortMapping {
        private String ip;
        private Integer privatePort;
        private Integer publicPort;
        private String type;
    }
}
