package com.opsmonitor.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 统一生命周期管理器（阶段6B 增强版）
 *
 * Spring 关闭时有序释放资源：
 * 1. 停止 Docker 事件监听
 * 2. 停止 HealthSupervisor 巡检线程
 * 3. 释放 RuntimeLockManager 文件锁
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleManager {

    private final DockerEventListener dockerEventListener;
    private final HealthSupervisor healthSupervisor;
    private final RuntimeLockManager lockManager;

    @EventListener(ContextClosedEvent.class)
    @Order(1)
    public void onShutdown(ContextClosedEvent event) {
        log.info("============================================");
        log.info("  系统正在关闭，执行优雅停止...");
        log.info("============================================");

        // 1. 停止事件监听
        try {
            dockerEventListener.stop();
            log.info("  ✅ Docker 事件监听已停止");
        } catch (Exception e) {
            log.warn("  ⚠️ 停止事件监听异常: {}", e.getMessage());
        }

        // 2. 停止巡检线程
        try {
            healthSupervisor.stop();
            log.info("  ✅ 巡检线程已停止");
        } catch (Exception e) {
            log.warn("  ⚠️ 停止巡检线程异常: {}", e.getMessage());
        }

        // 3. 释放文件锁
        try {
            lockManager.releaseLock();
            log.info("  ✅ 运行态锁已释放");
        } catch (Exception e) {
            log.warn("  ⚠️ 释放运行态锁异常: {}", e.getMessage());
        }

        log.info("  监控容器继续运行（Prometheus/Grafana/Exporter）");
        log.info("============================================");
        log.info("  优雅停止完成");
        log.info("============================================");
    }
}