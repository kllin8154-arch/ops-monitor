package com.opsmonitor.monitor;

import com.opsmonitor.config.OpsMonitorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus 热加载服务 — Scheduled Debounce Reload (9G-1)
 *
 * 解决 reload 风暴问题：
 * - 同时注册 100 个 Exporter 时，合并为 1 次 reload
 * - 2 秒合并窗口：收到 reload 请求后等 2 秒，期间新请求不触发额外 reload
 * - 最大重试 3 次
 */
@Slf4j
@Service
public class PrometheusReloadService {

    private final OpsMonitorProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ScheduledExecutorService debounceScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "prom-reload-debounce");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> pendingReload;
    private final AtomicLong lastReloadTime = new AtomicLong(0);
    private static final long DEBOUNCE_MS = 2000;
    private static final int MAX_RETRIES = 3;

    public PrometheusReloadService(OpsMonitorProperties properties) {
        this.properties = properties;
    }

    @PreDestroy
    public void shutdown() {
        debounceScheduler.shutdown();
    }

    /**
     * 请求 Prometheus reload（debounce 合并）
     * 2 秒内的多次调用只触发 1 次实际 reload
     */
    public synchronized void requestReload() {
        if (pendingReload != null && !pendingReload.isDone()) {
            pendingReload.cancel(false);
        }
        pendingReload = debounceScheduler.schedule(this::executeReload, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        log.debug("Prometheus reload 已排队（debounce {}ms）", DEBOUNCE_MS);
    }

    /**
     * 立即执行 reload（绕过 debounce，同步）
     */
    public boolean reloadNow() {
        return executeReload();
    }

    private boolean executeReload() {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String url = String.format("http://127.0.0.1:%d/-/reload",
                        properties.getPrometheus().getPort());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    lastReloadTime.set(System.currentTimeMillis());
                    log.info("Prometheus 热加载成功（debounce）");
                    return true;
                }
                log.warn("Prometheus reload HTTP {} (attempt {}/{})", response.statusCode(), attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("Prometheus reload 异常 (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
            }
            if (attempt < MAX_RETRIES) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        log.error("Prometheus reload 失败（已重试 {} 次）", MAX_RETRIES);
        return false;
    }

    public long getLastReloadTime() { return lastReloadTime.get(); }
}