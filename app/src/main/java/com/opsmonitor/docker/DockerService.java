package com.opsmonitor.docker;

import com.opsmonitor.model.ContainerInfo;
import com.opsmonitor.model.ContainerLogResponse;
import com.opsmonitor.model.ContainerStats;

import java.util.List;

/**
 * Docker 服务接口
 * 封装所有 Docker 容器操作，通过 docker-java SDK 实现
 * 不允许通过 shell 执行 docker 命令
 */
public interface DockerService {

    // ==================== 容器列表与查询 ====================

    /**
     * 获取所有容器
     * @param all true=包含已停止, false=仅运行中
     */
    List<ContainerInfo> listContainers(boolean all);

    /**
     * 获取容器列表（支持过滤）
     * @param all      是否包含已停止
     * @param status   状态过滤 (running/exited/created/paused), null=不过滤
     * @param name     名称模糊匹配, null=不过滤
     */
    List<ContainerInfo> listContainers(boolean all, String status, String name);

    /**
     * 获取容器详情
     */
    ContainerInfo getContainer(String containerId);

    // ==================== 容器生命周期控制 ====================

    /**
     * 启动容器
     */
    void startContainer(String containerId);

    /**
     * 停止容器
     * @param timeout 等待超时（秒），0=立即
     */
    void stopContainer(String containerId, int timeout);

    /**
     * 重启容器
     */
    void restartContainer(String containerId);

    /**
     * 删除容器
     * @param force 是否强制删除运行中的容器
     */
    void removeContainer(String containerId, boolean force);

    // ==================== 日志 ====================

    /**
     * 获取容器日志
     * @param containerId 容器ID
     * @param tail        最后N行
     * @param stdout      是否包含 stdout
     * @param stderr      是否包含 stderr
     * @param timestamps  是否包含时间戳
     */
    ContainerLogResponse getLogs(String containerId, int tail,
                                 boolean stdout, boolean stderr, boolean timestamps);

    // ==================== 资源监控 ====================

    /**
     * 获取容器资源统计（短时采样，不阻塞）
     */
    ContainerStats getStats(String containerId);

    // ==================== 工具方法 ====================

    /**
     * 检查容器是否存在
     */
    boolean containerExists(String containerId);

    /**
     * 检查容器是否正在运行
     */
    boolean isContainerRunning(String containerNameOrId);

    /**
     * 检查是否为受保护容器（Prometheus/Grafana/Exporter等）
     */
    boolean isProtectedContainer(String containerName);
}
