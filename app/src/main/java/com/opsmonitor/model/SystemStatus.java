package com.opsmonitor.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系统健康状态
 */
@Data
@Builder
public class SystemStatus {

    /** 系统是否就绪 */
    private boolean ready;

    /** Docker 是否连接 */
    private boolean dockerConnected;

    /** Docker 版本 */
    private String dockerVersion;

    /** Prometheus 是否运行 */
    private boolean prometheusRunning;

    /** Grafana 是否运行 */
    private boolean grafanaRunning;

    /** 操作系统 */
    private String osName;

    /** 启动时间 */
    private LocalDateTime startTime;

    /** 各组件状态 */
    private Map<String, String> componentStatus;
}
