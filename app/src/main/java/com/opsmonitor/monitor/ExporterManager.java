package com.opsmonitor.monitor;

import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.model.ExporterRegisterRequest;
import com.opsmonitor.model.ExporterTemplate;

import java.util.List;

/**
 * Exporter 管理接口
 * 职责：Exporter 模板管理、容器生命周期、Prometheus 自动注册
 *
 * 所有 Docker 操作通过 DockerService 执行（遵循 AGENT_RULES：不在 monitor 包直接调用 docker-java）
 * 所有 Prometheus 操作通过 PrometheusManager 执行
 */
public interface ExporterManager {

    // ==================== 模板管理 ====================

    /**
     * 获取所有内置 Exporter 模板
     */
    List<ExporterTemplate> listTemplates();

    /**
     * 根据类型获取模板
     */
    ExporterTemplate getTemplate(String type);

    // ==================== Exporter 生命周期 ====================

    /**
     * 注册并启动 Exporter
     * 1. 根据模板创建 Docker 容器
     * 2. 自动注册到 Prometheus
     * 3. 热加载 Prometheus 配置
     */
    ExporterInstance registerExporter(ExporterRegisterRequest request);

    /**
     * 注销 Exporter
     * 1. 停止并删除 Docker 容器
     * 2. 从 Prometheus 移除 scrape target
     * 3. 热加载 Prometheus 配置
     */
    void unregisterExporter(String exporterId);

    /**
     * 启动已注册的 Exporter
     */
    void startExporter(String exporterId);

    /**
     * 停止 Exporter（不注销）
     */
    void stopExporter(String exporterId);

    // ==================== 查询 ====================

    /**
     * 获取所有已注册的 Exporter 实例
     */
    List<ExporterInstance> listExporters();

    /**
     * 获取 Exporter 实例详情
     */
    ExporterInstance getExporter(String exporterId);

    /**
     * v2.13-A: Agent 级联状态更新
     * 将指定服务器下所有 Exporter 的 agentStatus 设为目标值
     * @param serverId 服务器节点 ID
     * @param agentStatus "AGENT_OFFLINE" / null（Agent 恢复时清除）
     */
    void cascadeAgentStatus(String serverId, String agentStatus);

    /**
     * v2.17: 更新 Exporter 标签（project/service）
     * 用于系统审计 Topology 维度优化
     */
    void updateLabels(String exporterId, String project, String service);
}
