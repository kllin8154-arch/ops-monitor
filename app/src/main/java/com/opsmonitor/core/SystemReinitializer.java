package com.opsmonitor.core;

import com.opsmonitor.docker.ComposeLauncher;
import com.opsmonitor.docker.DockerEnvironmentChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Docker 启动时序自动恢复 (v2.13-B)
 *
 * 场景：Spring Boot 启动时 Docker 尚未就绪（如开机后 Docker Desktop 延迟启动），
 * ComposeLauncher 初始化失败后，SystemReinitializer 在后台轮询检测，
 * 一旦 Docker 可用就自动拉起监控栈容器。
 *
 * 策略：
 *   - 每 30 秒检查一次，首次延迟 60 秒
 *   - 检查 Docker 可用性 → 可用则调用 composeUp() 启动监控栈
 *   - 成功启动后设置 initialized=true，后续轮询短路返回
 *   - 最多重试 10 次（5 分钟），超过后记录 ERROR 并停止
 *   - 所有注入使用 @Autowired(required=false)，避免 Docker 不存在时启动失败
 */
@Slf4j
@Component
public class SystemReinitializer {

    @Autowired(required = false)
    private DockerEnvironmentChecker dockerChecker;

    @Autowired(required = false)
    private ComposeLauncher composeLauncher;

    @Autowired(required = false)
    private com.opsmonitor.service.SystemInitializer systemInitializer;

    private boolean initialized = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 10;

    /**
     * 后台轮询：Docker 就绪后自动启动监控栈
     * 初始延迟 60 秒（给 Docker Desktop 足够的启动时间）
     * 每 30 秒检查一次
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void reinitializeIfNeeded() {
        if (initialized) {
            return;
        }

        // v2.27: 如果 SystemInitializer 已完成，本组件无需再重试
        if (systemInitializer != null && systemInitializer.isInitialized()) {
            initialized = true;
            return;
        }

        if (dockerChecker == null || composeLauncher == null) {
            log.warn("[SystemReinitializer] DockerChecker 或 ComposeLauncher 未注入（可能无 Docker 环境），停止重试");
            initialized = true; // 标记完成，不再重试
            return;
        }

        retryCount++;
        if (retryCount > MAX_RETRIES) {
            log.error("[SystemReinitializer] 已重试 {} 次（{} 分钟），Docker 仍不可用，停止重试。" +
                    "请手动执行 'docker compose up -d' 启动监控栈。", MAX_RETRIES, MAX_RETRIES / 2);
            initialized = true;
            return;
        }

        log.info("[SystemReinitializer] 第 {}/{} 次检查 Docker 可用性...", retryCount, MAX_RETRIES);

        if (!dockerChecker.isDockerAvailable()) {
            log.debug("[SystemReinitializer] Docker 不可用，{}s 后重试", 30);
            return;
        }

        log.info("[SystemReinitializer] Docker 已就绪，尝试启动监控栈...");
        try {
            boolean ok = composeLauncher.composeUp();
            if (ok) {
                log.info("[SystemReinitializer] ✅ 监控栈启动成功（第 {} 次尝试）", retryCount);
                initialized = true;
            } else {
                log.warn("[SystemReinitializer] composeUp() 返回 false，{}s 后重试", 30);
            }
        } catch (Exception e) {
            log.warn("[SystemReinitializer] composeUp() 异常: {}，{}s 后重试", e.getMessage(), 30);
        }
    }
}
