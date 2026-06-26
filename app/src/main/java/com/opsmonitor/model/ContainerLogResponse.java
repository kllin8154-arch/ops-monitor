package com.opsmonitor.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 容器日志响应模型
 */
@Data
@Builder
public class ContainerLogResponse {

    /** 容器ID */
    private String containerId;

    /** 容器名称 */
    private String containerName;

    /** 日志行数 */
    private int lineCount;

    /** 日志内容 */
    private String logs;

    /** 日志行列表（结构化） */
    private List<LogLine> lines;

    /** 是否包含 stdout */
    private boolean stdout;

    /** 是否包含 stderr */
    private boolean stderr;

    @Data
    @Builder
    public static class LogLine {
        private String timestamp;
        private String stream;  // stdout / stderr
        private String content;
    }
}
