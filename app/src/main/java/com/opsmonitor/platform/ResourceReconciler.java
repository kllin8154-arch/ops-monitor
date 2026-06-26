package com.opsmonitor.platform;

import com.opsmonitor.monitor.AlertRuleGenerator;
import com.opsmonitor.monitor.DashboardGenerator;
import com.opsmonitor.monitor.PrometheusReloadService;
import com.opsmonitor.monitor.PrometheusTargetWriter;
import com.opsmonitor.monitor.ExporterManager;
import com.opsmonitor.platform.model.ManagedResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 资源调谐器 (10D.1 Hotfix-4)
 *
 * 声明式系统核心：Resource 变化 → 自动生成对应配置。
 *
 * 类似 Kubernetes Reconciliation Loop：
 *   观察 Resource 状态 → 对比期望状态 → 执行变更
 *
 * 触发时机：
 * - 资源变更后手动调用 requestReconcile()
 * - 每 30 秒定时调谐（兜底）
 *
 * 调谐动作：
 * 1. ExporterResource 变更 → 重建 targets/*.json → Prometheus reload
 * 2. AlertRuleResource 变更 → 重建 alert.rules.yml → Prometheus reload
 * 3. DashboardResource 变更 → 重建 Grafana Dashboard JSON
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceReconciler {

    private final ExporterManager exporterManager;
    private final PrometheusTargetWriter targetWriter;
    private final PrometheusReloadService reloadService;
    private final AlertRuleGenerator alertRuleGenerator;
    private final DashboardGenerator dashboardGenerator;

    /** 脏标记：有资源变更待调谐（初始为 true，启动时必须至少调谐一次） */
    private final AtomicBoolean dirty = new AtomicBoolean(true);

    /** 上次调谐时间 */
    private volatile long lastReconcileTime = 0;

    /**
     * 标记需要调谐（资源变更后调用）
     */
    public void requestReconcile() {
        dirty.set(true);
    }

    /**
     * 30 秒定时调谐（兜底）
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 5000)  // V6: 启动5秒后立即生成仪表盘
    public void scheduledReconcile() {
        if (dirty.compareAndSet(true, false)) {
            reconcile();
        }
    }

    /**
     * 立即调谐
     */
    public void reconcileNow() {
        dirty.set(false);
        reconcile();
    }

    /**
     * 执行调谐
     */
    private void reconcile() {
        long start = System.currentTimeMillis();
        log.info("[Reconciler] 开始调谐...");
        int actions = 0;

        try {
            // 1. 重建 Prometheus targets
            var exporters = exporterManager.listExporters();
            if (!exporters.isEmpty()) {
                targetWriter.writeAllTargets(exporters);
                actions++;
            }

            // 2. 重建告警规则
            alertRuleGenerator.generateAlertRules();
            actions++;

            // 3. 重建 Dashboard
            dashboardGenerator.generateAllDashboards();
            actions++;

            // 4. 触发 Prometheus reload
            reloadService.requestReload();

            lastReconcileTime = System.currentTimeMillis();
            log.info("[Reconciler] 调谐完成: {} 个动作, 耗时 {}ms",
                    actions, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[Reconciler] 调谐异常: {}", e.getMessage());
            // 标记脏，下次重试
            dirty.set(true);
        }
    }

    public long getLastReconcileTime() { return lastReconcileTime; }
    public boolean isDirty() { return dirty.get(); }
}