package com.opsmonitor.service;

/**
 * 服务控制统一接口 - 服务抽象层
 * 所有服务（Docker 服务、系统服务）都必须实现此接口
 */
public interface ServiceController {

    /**
     * 启动服务
     */
    void start();

    /**
     * 停止服务
     */
    void stop();

    /**
     * 重启服务
     */
    void restart();

    /**
     * 获取服务状态
     */
    ServiceStatus status();

    /**
     * 服务状态枚举
     */
    enum ServiceStatus {
        RUNNING,
        STOPPED,
        STARTING,
        STOPPING,
        ERROR,
        UNKNOWN
    }
}
