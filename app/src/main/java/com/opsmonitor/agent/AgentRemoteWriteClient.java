package com.opsmonitor.agent;

import com.opsmonitor.config.OpsMonitorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent Remote Write 客户端 V2 (10C-1)
 *
 * P0 修复：
 * - WAL 队列：Victoria 挂了数据不丢
 * - Backpressure：队列满时 drop oldest
 * - Retry：发送失败自动回写队列 + WAL 持久化
 * - Rate Limit：最大 100 samples/batch
 * - 崩溃恢复：重启后从 WAL 文件恢复
 *
 * 数据流：
 *   Collector → MetricBufferQueue (内存+WAL) → flush → VictoriaMetrics
 */
@Slf4j
@Service
public class AgentRemoteWriteClient {

    private final AgentCollectorService collector;
    private final OpsMonitorProperties properties;
    private MetricBufferQueue bufferQueue;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong failedWrites = new AtomicLong(0);
    // v2.30: 连续失败计数（日志降频用）
    private volatile int consecutiveFailures = 0;

    private static final String VM_IMPORT_PATH = "/api/v1/import/prometheus";
    private static final int FLUSH_BATCH_SIZE = 100;
    private static final int MAX_RETRY = 3;

    public AgentRemoteWriteClient(AgentCollectorService collector,
                                  OpsMonitorProperties properties) {
        this.collector = collector;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String walDir = properties.getCompose().getWorkDir() + "/../data/agent-wal";
        this.bufferQueue = new MetricBufferQueue(walDir);
        log.info("[RemoteWrite V2] WAL 已初始化, 队列恢复 {} 条", bufferQueue.size());
    }

    /**
     * 15 秒采集并入队
     */
    @Scheduled(fixedDelay = 15000, initialDelay = 30000)
    public void collectAndEnqueue() {
        try {
            String agentId = getAgentId();
            Map<String, String> labels = buildBaseLabels(agentId);
            var samples = collector.collectAll(agentId, labels);
            if (samples.isEmpty()) return;

            String body = collector.toPrometheusTextFormat(samples);
            bufferQueue.enqueue(body);
        } catch (Exception e) {
            log.debug("[RemoteWrite] 采集异常: {}", e.getMessage());
        }
    }

    /**
     * 5 秒 flush 一次队列到 VictoriaMetrics
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 35000)
    public void flushQueue() {
        if (bufferQueue.isEmpty()) return;

        List<String> batch = bufferQueue.dequeueBatch(FLUSH_BATCH_SIZE);
        if (batch.isEmpty()) return;

        String combined = String.join("", batch);
        boolean success = pushWithRetry(combined, MAX_RETRY);

        if (success) {
            totalWrites.incrementAndGet();
            consecutiveFailures = 0;
            bufferQueue.markFlushed(batch.size());
            bufferQueue.cleanWal();
        } else {
            failedWrites.incrementAndGet();
            bufferQueue.requeueAndPersist(batch);
            // v2.30: 连续失败降频——第1次和第10的倍数才打 WARN
            if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
                log.warn("[RemoteWrite] flush 失败(连续第{}次), {} 条回写队列+WAL (队列: {})",
                        consecutiveFailures, batch.size(), bufferQueue.size());
            } else {
                log.debug("[RemoteWrite] flush 失败(连续第{}次)", consecutiveFailures);
            }
            consecutiveFailures++;
        }
    }

    private boolean pushWithRetry(String body, int maxRetry) {
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                String url = getVictoriaMetricsUrl() + VM_IMPORT_PATH;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 204 || response.statusCode() == 200) {
                    return true;
                }

                log.debug("[RemoteWrite] attempt {}/{} HTTP {}", attempt, maxRetry, response.statusCode());
            } catch (Exception e) {
                log.debug("[RemoteWrite] attempt {}/{} 失败: {}", attempt, maxRetry, e.getMessage());
            }

            // 退避等待
            if (attempt < maxRetry) {
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        // 关闭前 flush 残留数据到 WAL
        if (bufferQueue != null && !bufferQueue.isEmpty()) {
            List<String> remaining = bufferQueue.dequeueBatch(bufferQueue.size());
            if (!remaining.isEmpty()) {
                bufferQueue.requeueAndPersist(remaining);
                log.info("[RemoteWrite] 关闭前已将 {} 条持久化到 WAL", remaining.size());
            }
        }
    }

    private String getVictoriaMetricsUrl() {
        // N9 修复：从配置读取，不再硬编码 127.0.0.1:8428
        // 配置项：ops-monitor.victoria.url（默认 http://127.0.0.1:8428）
        String url = properties.getVictoria().getUrl();
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8428";
        }
        // 移除末尾斜杠，统一格式
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String getAgentId() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "local-agent"; }
    }

    private Map<String, String> buildBaseLabels(String agentId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("agent_id", agentId);
        labels.put("managed_by", "ops-monitor");
        labels.put("source", "agent-remote-write");
        return labels;
    }

    public long getTotalWrites() { return totalWrites.get(); }
    public long getFailedWrites() { return failedWrites.get(); }
    public int getQueueSize() { return bufferQueue != null ? bufferQueue.size() : 0; }
    public long getQueueDropped() { return bufferQueue != null ? bufferQueue.getTotalDropped() : 0; }
}