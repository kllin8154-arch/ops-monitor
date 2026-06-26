package com.opsmonitor.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Exporter 模板定义
 * 描述一个 Exporter 的 Docker 镜像、端口、环境变量等
 */
@Data
@Builder
public class ExporterTemplate {

    /** 模板标识（如 redis、mysql、nginx） */
    private String type;

    /** 显示名称 */
    private String displayName;

    /** Docker 镜像 */
    private String image;

    /** Exporter 容器名称前缀（实际名 = ops-{type}-exporter） */
    private String containerNamePrefix;

    /** Exporter 指标暴露端口 */
    private int metricsPort;

    /** 容器内部端口 */
    private int containerPort;

    /** Prometheus job 名称 */
    private String jobName;

    /** 默认环境变量 */
    private Map<String, String> defaultEnv;

    /** 启动命令 / 参数 */
    private List<String> command;

    /** 需要用户提供的连接参数说明 */
    private List<String> requiredParams;

    /** Prometheus scrape target（容器网络内的地址） */
    private String scrapeTarget;

    /** 该 Exporter 所属分类 */
    private String category;

    /**
     * 手动部署提示命令 (9F-4)
     * 当用户在远程服务器上部署 Exporter 时，系统可以生成参考 docker run 命令
     */
    @Builder.Default
    private String dockerRunHint = "";
}