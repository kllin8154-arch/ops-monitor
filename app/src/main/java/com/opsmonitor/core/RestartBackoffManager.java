package com.opsmonitor.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重启退避管理器
 *
 * 线程安全保证：
 * - ConcurrentHashMap 保证 entry 级并发
 * - 每个 RestartState 的读写通过 synchronized(state) 保护
 * - allowRestart + recordRestart 可安全从 HealthSupervisor 和 DockerEventListener 并发调用
 *
 * 内存安全：
 * - 每次 recordRestart 时顺带清理超过 10 分钟无活动的旧 entry
 * - 防止已删除容器的记录永久留存
 */
@Slf4j
@Component
public class RestartBackoffManager {

    private static final int WINDOW_SECONDS = 300;
    private static final int MAX_RESTARTS = 5;
    private static final int BLOCK_SECONDS = 300;
    /** 过期清理阈值：10 分钟无活动的 entry 自动移除 */
    private static final int EVICT_SECONDS = 600;

    private final ConcurrentHashMap<String, RestartState> states = new ConcurrentHashMap<>();

    /**
     * 检查是否允许重启（线程安全）
     */
    public boolean allowRestart(String containerName) {
        RestartState state = states.computeIfAbsent(containerName, k -> new RestartState());
        synchronized (state) {
            Instant now = Instant.now();

            // 阻断期内
            if (state.blockedUntil != null && now.isBefore(state.blockedUntil)) {
                return false;
            }

            // 阻断期已过，重置
            if (state.blockedUntil != null) {
                state.restartCount = 0;
                state.windowStart = now;
                state.blockedUntil = null;
            }

            return true;
        }
    }

    /**
     * 记录一次重启（线程安全）
     */
    public void recordRestart(String containerName) {
        RestartState state = states.computeIfAbsent(containerName, k -> new RestartState());
        synchronized (state) {
            Instant now = Instant.now();

            // 窗口过期，重新计数
            if (state.windowStart == null
                    || now.isAfter(state.windowStart.plusSeconds(WINDOW_SECONDS))) {
                state.windowStart = now;
                state.restartCount = 0;
            }

            state.restartCount++;
            state.lastActivity = now;

            if (state.restartCount >= MAX_RESTARTS) {
                state.blockedUntil = now.plusSeconds(BLOCK_SECONDS);
                log.error("[RestartBackoff] 容器 {} 在 {}s 内重启 {} 次，" +
                                "自动阻断 {}s，请人工排查",
                        containerName, WINDOW_SECONDS, state.restartCount, BLOCK_SECONDS);
            }
        }

        // 顺带清理过期 entry（低频操作，不影响性能）
        evictStaleEntries();
    }

    /**
     * 手动重置指定容器
     */
    public void reset(String containerName) {
        states.remove(containerName);
        log.info("[RestartBackoff] 已重置容器 {} 的退避状态", containerName);
    }

    /**
     * 重置所有
     */
    public void resetAll() {
        states.clear();
        log.info("[RestartBackoff] 已重置所有退避状态");
    }

    /**
     * 状态摘要
     */
    public String getSummary(String containerName) {
        RestartState state = states.get(containerName);
        if (state == null) return "无记录";
        synchronized (state) {
            if (state.blockedUntil != null && Instant.now().isBefore(state.blockedUntil)) {
                return String.format("已阻断 (重启%d次, 解除于%s)", state.restartCount, state.blockedUntil);
            }
            return String.format("正常 (窗口内重启%d次)", state.restartCount);
        }
    }

    /**
     * 清理超过 EVICT_SECONDS 无活动的旧 entry
     */
    private void evictStaleEntries() {
        Instant cutoff = Instant.now().minusSeconds(EVICT_SECONDS);
        Iterator<Map.Entry<String, RestartState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RestartState> entry = it.next();
            RestartState state = entry.getValue();
            synchronized (state) {
                if (state.lastActivity != null && state.lastActivity.isBefore(cutoff)
                        && state.blockedUntil == null) {
                    it.remove();
                }
            }
        }
    }

    private static class RestartState {
        Instant windowStart;
        Instant lastActivity;
        Instant blockedUntil;
        int restartCount;
    }
}