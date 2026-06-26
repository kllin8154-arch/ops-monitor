package com.opsmonitor.monitor;

/**
 * Grafana 管理接口
 */
public interface GrafanaManager {

    /**
     * 轮询等待 Grafana 就绪
     * 检测 /api/health 返回 {"database":"ok"}
     * @return true=就绪, false=超时
     */
    boolean waitUntilReady();

    /**
     * 检查指定名称的数据源是否已存在
     */
    boolean datasourceExists(String name);

    /**
     * 创建 Prometheus 数据源
     */
    void createPrometheusDatasource();

    /**
     * Grafana 初始化总流程
     * 1. 等待就绪
     * 2. 检查数据源
     * 3. 创建数据源（如不存在）
     *
     * 失败不抛异常，仅日志记录
     */
    void initializeGrafana();

    /**
     * 导入 Dashboard
     * @param dashboardJson Dashboard JSON 内容
     */
    boolean importDashboard(String dashboardJson);

    /**
     * 检查 Grafana 是否运行
     */
    boolean isRunning();

    /**
     * 获取 Grafana 嵌入 URL
     */
    String getEmbedUrl();

    /**
     * 触发 Grafana Dashboard provisioning 重新加载 (9F-2)
     * POST /api/admin/provisioning/dashboards/reload
     */
    boolean reloadDashboards();
}