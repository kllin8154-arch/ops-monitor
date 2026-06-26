package com.opsmonitor.monitor;

import com.opsmonitor.model.ExporterInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exporter 对账服务 (BUG-HOST-DOWN 修复 - 数据残留防护)
 *
 * 问题背景：
 *   exporters.json 中仅登记 2 个 Exporter，但 Grafana 蜂窝图显示 5 个格子。
 *   原因之一：启动补注册（ExporterManagerImpl.loadExportersFromJsonInternal）
 *   只向 Prometheus addScrapeTarget，但未强制重写 File SD 文件，
 *   导致历史遗留的 docker/targets/*.json 条目成为幽灵 target。
 *
 * 本类职责：
 *   每 2 分钟执行一次全量对账：
 *     1. 以 exporterInstances（内存 Map）为权威数据源
 *     2. 调用 targetWriter.writeAllTargets() 覆盖 File SD 文件
 *     3. 触发 Prometheus reload（防止 Prometheus 继续抓取幽灵 target）
 *
 * 遵循 AGENT_RULES：
 *   - 扁平包，不新增子包
 *   - 新增类，不重构已有模块
 *   - 不引入新依赖
 */
@Slf4j
@Service
public class ExporterReconciler {

    private final ExporterManager exporterManager;
    private final PrometheusTargetWriter targetWriter;
    private final PrometheusReloadService reloadService;

    /** 对账执行计数（供监控 / 诊断）*/
    private final AtomicLong reconcileCount = new AtomicLong(0);
    /** 上次对账时间戳 */
    private final AtomicLong lastReconcileTime = new AtomicLong(0);

    public ExporterReconciler(ExporterManager exporterManager,
                              PrometheusTargetWriter targetWriter,
                              PrometheusReloadService reloadService) {
        this.exporterManager = exporterManager;
        this.targetWriter = targetWriter;
        this.reloadService = reloadService;
    }

    /**
     * 每 120 秒执行一次对账（应用启动 90s 后开始，确保 ExporterManager 已 load）
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    public void reconcile() {
        try {
            List<ExporterInstance> snapshot = exporterManager.listExporters();
            if (snapshot == null) return;

            // 关键动作：以内存为权威，强制重写 File SD
            // writeAllTargets 内部有 ReentrantLock 保护，无并发风险
            targetWriter.writeAllTargets(snapshot);

            // 触发 debounce reload（2s 内多次调用会合并为一次）
            reloadService.requestReload();

            long count = reconcileCount.incrementAndGet();
            lastReconcileTime.set(System.currentTimeMillis());

            // 每 10 次对账打印一次 info（约 20 分钟），其余 debug
            if (count % 10 == 1) {
                log.info("[Reconciler] 完成第 {} 次 Exporter 对账，当前 {} 个实例",
                        count, snapshot.size());
            } else {
                log.debug("[Reconciler] 对账 #{}，{} 个 Exporter 实例", count, snapshot.size());
            }
        } catch (Exception e) {
            log.warn("[Reconciler] 对账异常：{}", e.getMessage());
        }
    }

    /** 对外查询：对账总次数 */
    public long getReconcileCount() {
        return reconcileCount.get();
    }

    /** 对外查询：上次对账时间 */
    public long getLastReconcileTime() {
        return lastReconcileTime.get();
    }

    /** 手动触发对账（供故障排查） */
    public void reconcileNow() {
        reconcile();
    }
}