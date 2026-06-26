package com.opsmonitor.core;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.opsmonitor.docker.ComposeLauncher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Docker 事件监听器
 *
 * 监听 Docker 引擎事件流（docker events），当核心容器 die 时即时触发恢复。
 * 作为 HealthSupervisor 轮询的补充：
 * - 事件监听：即时响应（毫秒级）
 * - 轮询巡检：兜底保障（30 秒级）
 *
 * 监听事件：container.die
 * 关注容器：ops-prometheus, ops-grafana, ops-node-exporter
 *
 * 线程安全：
 * - Docker events 回调在 docker-java 内部线程执行
 * - 恢复操作通过 RestartBackoffManager 控制频率
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerEventListener {

    private final DockerClient dockerClient;
    private final ComposeLauncher composeLauncher;
    private final RestartBackoffManager backoffManager;

    /** 需要监听的核心容器名 */
    private static final Set<String> WATCHED_CONTAINERS = Set.of(
            "ops-prometheus", "ops-grafana", "ops-node-exporter"
    );

    private Closeable eventStream;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void start() {
        try {
            eventStream = dockerClient.eventsCmd()
                    .withEventTypeFilter(EventType.CONTAINER)
                    .withEventFilter("die")
                    .exec(new ResultCallback.Adapter<Event>() {
                        @Override
                        public void onNext(Event event) {
                            handleEvent(event);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            if (running.get()) {
                                log.warn("[DockerEvents] 事件流异常: {} (将由轮询兜底)",
                                        throwable.getMessage());
                            }
                        }
                    });

            running.set(true);
            log.info("DockerEventListener 已启动，监听容器 die 事件");

        } catch (Exception e) {
            log.warn("DockerEventListener 启动失败（轮询巡检仍有效）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (eventStream != null) {
            // 在独立线程中关闭，防止阻塞 JVM shutdown
            Thread closer = new Thread(() -> {
                try {
                    eventStream.close();
                } catch (IOException e) {
                    // 忽略关闭异常
                }
            }, "docker-event-closer");
            closer.setDaemon(true);
            closer.start();
            try {
                closer.join(3000);  // 最多等 3 秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("DockerEventListener 已停止");
    }

    private void handleEvent(Event event) {
        if (!running.get()) return;

        String containerName = extractContainerName(event);
        if (containerName == null) return;

        // 只关注核心容器
        if (!WATCHED_CONTAINERS.contains(containerName)
                && !containerName.startsWith("ops-")) {
            return;
        }

        String action = event.getAction();
        log.warn("[DockerEvents] 容器事件: {} action={}", containerName, action);

        if ("die".equals(action) && WATCHED_CONTAINERS.contains(containerName)) {
            // 检查退避
            if (!backoffManager.allowRestart(containerName)) {
                log.warn("[DockerEvents] 容器 {} 处于退避阻断中，跳过自动恢复", containerName);
                return;
            }

            log.warn("[DockerEvents] 核心容器 {} 已终止，3 秒后尝试恢复...", containerName);
            backoffManager.recordRestart(containerName);

            // 延迟 3 秒再恢复（给容器清理时间）
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    if (!running.get()) return;

                    dockerClient.startContainerCmd(containerName).exec();
                    log.info("[DockerEvents] 容器已恢复: {}", containerName);
                } catch (Exception e) {
                    log.warn("[DockerEvents] 直接 start 失败，尝试 compose up: {}", e.getMessage());
                    try {
                        composeLauncher.composeUp();
                    } catch (Exception ex) {
                        log.error("[DockerEvents] compose up 也失败: {}", ex.getMessage());
                    }
                }
            }, "docker-event-recover-" + containerName).start();
        }
    }

    /**
     * 从事件中提取容器名称
     */
    private String extractContainerName(Event event) {
        if (event.getActor() != null && event.getActor().getAttributes() != null) {
            return event.getActor().getAttributes().get("name");
        }
        return null;
    }

    public boolean isRunning() {
        return running.get();
    }
}