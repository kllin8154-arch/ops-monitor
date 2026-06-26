package com.opsmonitor.monitor;

import java.util.List;
import java.util.Map;

/**
 * Prometheus 管理接口
 * 负责 prometheus.yml 配置的动态管理和热加载
 */
public interface PrometheusManager {

    /**
     * 添加 scrape target
     * @param jobName 任务名称（唯一）
     * @param targets 目标地址列表
     * @param labels  额外标签
     */
    void addScrapeTarget(String jobName, List<String> targets, Map<String, String> labels);

    /**
     * 移除 scrape target
     * @param jobName 任务名称
     */
    void removeScrapeTarget(String jobName);

    /**
     * 获取当前所有 scrape job 名称
     */
    List<String> listScrapeJobs();

    /**
     * 热加载 Prometheus 配置（POST /-/reload）
     */
    boolean reloadConfig();

    /**
     * 检查 Prometheus 是否运行
     */
    boolean isRunning();
}