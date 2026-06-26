package com.opsmonitor.core;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.opsmonitor.docker.ComposeLauncher;
import com.opsmonitor.monitor.ExporterManagerImpl;
import com.opsmonitor.monitor.GrafanaManager;
import com.opsmonitor.monitor.PrometheusManager;
import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.service.SystemInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 健康巡检守护线程（阶段6B 增强版）
 *
 * 30 秒周期巡检 + RestartBackoffManager 退避控制
 * 与 DockerEventListener 配合：事件即时响应 + 轮询兜底
 *
 * 日志等级：
 * - 自动恢复行为 → WARN
 * - 阻断/严重异常 → ERROR
 * - 正常运行/恢复成功 → INFO
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthSupervisor {

    private final DockerClient dockerClient;
    private final PrometheusManager prometheusManager;
    private final GrafanaManager grafanaManager;
    private final ComposeLauncher composeLauncher;
    private final ExporterManagerImpl exporterManager;
    private final RestartBackoffManager backoffManager;
    private final SystemInitializer systemInitializer;

    private static final int CHECK_INTERVAL = 30;
    private static final int INITIAL_DELAY = 60;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int consecutiveFailures = 0;

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-supervisor");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(
                this::performHealthCheck, INITIAL_DELAY, CHECK_INTERVAL, TimeUnit.SECONDS
        );

        running.set(true);
        log.info("HealthSupervisor 已启动 (延迟 {}s, 间隔 {}s)", INITIAL_DELAY, CHECK_INTERVAL);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("HealthSupervisor 已停止");
    }

    private void performHealthCheck() {
        if (!running.get()) return;
        // v2.27: 等待 SystemInitializer 完成后再巡检
        if (!systemInitializer.isInitialized()) return;

        try {
            boolean allHealthy = true;

            // 1. Prometheus
            if (!prometheusManager.isRunning()) {
                allHealthy = false;
                attemptRecover("ops-prometheus", "Prometheus");
            }

            // 2. Grafana
            if (!grafanaManager.isRunning()) {
                allHealthy = false;
                attemptRecover("ops-grafana", "Grafana");
            }

            // 3. Exporter 容器
            List<ExporterInstance> exporters = exporterManager.listExporters();
            for (ExporterInstance inst : exporters) {
                if (!inst.isManagedByDocker()) continue; // 远程 Exporter 跳过容器检查
                String state = inst.getState();
                // 9H-4: 扩展恢复触发条件
                if ("exited".equals(state) || "dead".equals(state)
                        || "missing".equals(state) || "unknown".equals(state)
                        || "created".equals(state)) {
                    allHealthy = false;
                    attemptRecoverExporter(inst);
                }
            }

            if (allHealthy) {
                if (consecutiveFailures > 0) {
                    log.info("[HealthCheck] 所有组件已恢复正常");
                }
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
            }

        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
                log.error("[HealthCheck] 巡检异常 (第 {} 次): {}", consecutiveFailures, e.getMessage());
            }
        }
    }

    /**
     * 尝试恢复基础设施容器（带退避检查）
     */
    private void attemptRecover(String containerName, String displayName) {
        // 退避检查
        if (!backoffManager.allowRestart(containerName)) {
            // 每 5 次巡检打一次阻断日志，避免洪泛
            if (consecutiveFailures % 5 == 1) {
                log.error("[HealthCheck] {} 处于重启退避中 ({})",
                        displayName, backoffManager.getSummary(containerName));
            }
            return;
        }

        log.warn("[HealthCheck] {} 不可达，尝试恢复...", displayName);

        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerName).exec();
            String status = inspect.getState() != null ? inspect.getState().getStatus() : "unknown";

            if ("exited".equals(status) || "created".equals(status)) {
                dockerClient.startContainerCmd(containerName).exec();
                backoffManager.recordRestart(containerName);
                log.info("[HealthCheck] {} 已重启", displayName);
            } else if ("running".equals(status)) {
                // 容器在跑但服务不可达，等待即可
            } else {
                composeLauncher.composeUp();
                backoffManager.recordRestart(containerName);
                log.info("[HealthCheck] 已执行 compose up 恢复 {}", displayName);
            }
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.warn("[HealthCheck] {} 容器不存在，执行 compose up", displayName);
            composeLauncher.composeUp();
            backoffManager.recordRestart(containerName);
        } catch (Exception e) {
            log.error("[HealthCheck] 恢复 {} 失败: {}", displayName, e.getMessage());
        }
    }

    /**
     * 尝试恢复 Exporter 容器（9H-4 增强版）
     * - 先 inspect 检查容器是否存在
     * - 存在但停止 → docker start
     * - 不存在 → 标记 missing
     */
    private void attemptRecoverExporter(ExporterInstance instance) {
        String name = instance.getContainerName();

        if (!instance.isManagedByDocker()) return;

        if (!backoffManager.allowRestart(name)) {
            if (consecutiveFailures % 5 == 1) {
                log.error("[HealthCheck] Exporter {} 处于退避阻断中", instance.getId());
            }
            return;
        }

        log.warn("[HealthCheck] Exporter {} 状态: {}，尝试恢复", instance.getId(), instance.getState());
        try {
            // 先检查容器是否存在
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(name).exec();
            String containerState = inspect.getState() != null ? inspect.getState().getStatus() : "unknown";

            if ("running".equals(containerState)) {
                instance.setState("running");
                return; // 已在运行
            }

            // 9H-4: 使用 docker restart 代替 docker start（更可靠）
            dockerClient.restartContainerCmd(name).withTimeout(10).exec();
            instance.setState("running");
            backoffManager.recordRestart(name);
            log.info("[HealthCheck] Exporter {} 已 restart", instance.getId());

        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            // 容器不存在
            log.warn("[HealthCheck] Exporter {} 容器已丢失，标记 missing", instance.getId());
            instance.setState("missing");
        } catch (Exception e) {
            log.error("[HealthCheck] 恢复 Exporter {} 失败: {}", instance.getId(), e.getMessage());
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}