package com.opsmonitor.service;

import com.opsmonitor.model.ServerNode;
import com.opsmonitor.monitor.DashboardGenerator;
import com.opsmonitor.monitor.GrafanaManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;


/**
 * Grafana 仪表盘同步服务
 *
 * 职责：在 Exporter / 服务器发生变更时，自动完成：
 *   1. 重新生成受影响服务器的专属仪表盘（server-{id}.json）
 *   2. 重新生成全局仪表盘（global-overview / service-overview / exporter-health）
 *   3. 调用 Grafana provisioning reload API，使 Grafana 立即加载最新文件
 *
 * 触发时机（全部异步，不阻塞主流程）：
 *   - ExporterManagerImpl.unregisterExporterInternal() 注销 Exporter 后
 *   - ServerService.deleteServer()              删除服务器后（级联）
 *   - ExporterManagerImpl.registerExporterInternal() 注册 Exporter 后
 *   - ServerService.addServer() / updateServer() 新增/更新服务器后
 *
 * 防抖：连续操作（批量级联删除）合并为一次 Grafana reload，
 *   通过 debounce 机制（500ms 窗口）避免 Grafana API 被频繁调用。
 */
@Slf4j
@Service
public class GrafanaSyncService {

    private final GrafanaManager         grafanaManager;
    private final DashboardGenerator     dashboardGenerator;

    /** @Lazy 避免循环依赖：ServerService → GrafanaSyncService → ServerService */
    @Lazy
    @Autowired
    private ServerDashboardService serverDashboardService;

    @Lazy
    @Autowired
    private ServerService serverService;

    public GrafanaSyncService(GrafanaManager grafanaManager,
                              DashboardGenerator dashboardGenerator) {
        this.grafanaManager     = grafanaManager;
        this.dashboardGenerator = dashboardGenerator;
    }

    /** 防抖锁：保证 Grafana reload 不被重复触发 */
    private volatile long lastReloadRequestMs = 0;
    private static final long DEBOUNCE_MS = 800;

    /**
     * P1-2 fix: 用单线程 ScheduledExecutorService 替代 new Thread()
     * 避免高频调用时产生大量短命线程
     */
    private final java.util.concurrent.ScheduledExecutorService debounceExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "grafana-sync-debounce");
                t.setDaemon(true);
                return t;
            });
    private volatile java.util.concurrent.ScheduledFuture<?> pendingReload = null;

    // ==================== 对外触发点 ====================

    /**
     * Exporter 注销后触发（异步）
     * 更新：该 Exporter 所属服务器的专属仪表盘 + 全局仪表盘 + Grafana reload
     */
    @Async
    public void onExporterUnregistered(String serverId, String exporterType) {
        log.info("[GrafanaSync] Exporter 已注销: serverId={}, type={}，触发仪表盘同步", serverId, exporterType);
        syncServerDashboard(serverId);
        syncGlobalDashboards();
        scheduleGrafanaReload();
    }

    /**
     * Exporter 注册后触发（异步）
     * 更新：该 Exporter 所属服务器的专属仪表盘 + 全局仪表盘 + Grafana reload
     */
    @Async
    public void onExporterRegistered(String serverId, String exporterType) {
        log.info("[GrafanaSync] Exporter 已注册: serverId={}, type={}，触发仪表盘同步", serverId, exporterType);
        syncServerDashboard(serverId);
        syncGlobalDashboards();
        scheduleGrafanaReload();
    }

    /**
     * 服务器删除后触发（异步）
     * 仅更新全局仪表盘 + Grafana reload
     * （专属仪表盘文件已由 ServerDashboardService.onServerDeleted() 删除）
     */
    @Async
    public void onServerDeleted(String deletedServerId) {
        log.info("[GrafanaSync] 服务器已删除: {}，触发全局仪表盘同步", deletedServerId);
        syncGlobalDashboards();
        // 同时更新本机仪表盘（本机 Docker 面板内容可能有变化）
        syncServerDashboard("local");
        scheduleGrafanaReload();
    }

    /**
     * 服务器新增/更新后触发（异步）
     */
    @Async
    public void onServerChanged(ServerNode server) {
        log.info("[GrafanaSync] 服务器变更: {}，触发仪表盘同步", server.getName());
        syncServerDashboard(server.getId());
        syncGlobalDashboards();
        scheduleGrafanaReload();
    }

    /**
     * 手动触发全量同步（供 API 接口调用）
     */
    @Async
    public void syncAll() {
        log.info("[GrafanaSync] 手动触发全量仪表盘同步");
        // 1. 重建所有服务器专属仪表盘
        serverDashboardService.generateAllServerDashboards();
        // 2. 重建全局仪表盘
        syncGlobalDashboards();
        // 3. Grafana reload
        doGrafanaReload();
        log.info("[GrafanaSync] 全量同步完成");
    }

    // ==================== 内部实现 ====================

    /**
     * 重建单台服务器的专属仪表盘
     * 从 ServerService 查最新服务器信息，确保数据一致
     */
    private void syncServerDashboard(String serverId) {
        try {
            ServerNode server = serverService.getServer(serverId);
            serverDashboardService.onServerUpdated(server);
            log.info("[GrafanaSync] 服务器专属仪表盘已重建: {}", server.getName());
        } catch (Exception e) {
            // 服务器已被删除 → 忽略（文件已由 onServerDeleted 清理）
            log.debug("[GrafanaSync] 重建服务器仪表盘跳过 (serverId={}): {}", serverId, e.getMessage());
        }
    }

    /**
     * 重建全局仪表盘文件
     *
     * V6 架构：只有 3 个仪表盘文件：
     *   infra-overview.json / service-health.json / middleware.json
     * 通过变量（server_name）在单个仪表盘内切换服务器，
     * 无需为每台服务器生成专属文件。
     */
    private void syncGlobalDashboards() {
        try {
            // DashboardGenerator.generateAllDashboards() 是幂等的，多次调用安全
            // V6：生成 3 个精简仪表盘，同时清理废弃的旧仪表盘文件
            dashboardGenerator.generateAllDashboards();
            log.info("[GrafanaSync] V6 全局仪表盘已重建（基础设施总览 / 服务健康总览 / 数据库与中间件）");
        } catch (Exception e) {
            log.error("[GrafanaSync] 重建全局仪表盘失败: {}", e.getMessage());
        }
    }

    /**
     * P1-2 fix: 防抖 Grafana reload — 用 ScheduledExecutorService 替代 new Thread()
     * 每次调用取消上一个待执行任务，只执行最后一次（标准防抖模式）
     */
    private void scheduleGrafanaReload() {
        lastReloadRequestMs = System.currentTimeMillis();
        // 取消上一个待执行的 reload（若还没执行）
        java.util.concurrent.ScheduledFuture<?> prev = pendingReload;
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
        // 延迟 DEBOUNCE_MS 后执行
        pendingReload = debounceExecutor.schedule(() -> {
            try {
                doGrafanaReload();
            } catch (Exception e) {
                log.error("[GrafanaSync] reload 异常: {}", e.getMessage());
            }
        }, DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 实际执行 Grafana provisioning reload
     */
    private void doGrafanaReload() {
        try {
            boolean ok = grafanaManager.reloadDashboards();
            if (ok) {
                log.info("[GrafanaSync] ✅ Grafana provisioning reload 成功");
            } else {
                log.warn("[GrafanaSync] ⚠️ Grafana provisioning reload 失败（Grafana 可能未运行）");
            }
        } catch (Exception e) {
            log.warn("[GrafanaSync] Grafana reload 异常: {}", e.getMessage());
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        debounceExecutor.shutdownNow();
    }
}