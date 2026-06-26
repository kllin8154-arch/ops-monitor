package com.opsmonitor.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics 缓冲队列 (10C-1 + P1-6 WAL 大小限制)
 *
 * P1-6 修复：
 * - MAX_WAL_SIZE_MB = 100：WAL 总大小超过后删除最旧文件
 * - persistToWal 前检查磁盘空间
 */
@Slf4j
public class MetricBufferQueue {

    private final ConcurrentLinkedQueue<String> memoryQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalFlushed = new AtomicLong(0);

    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int MAX_BATCH_SIZE = 500;
    /** P1-6: WAL 总大小上限 (MB) */
    private static final long MAX_WAL_SIZE_MB = 100;

    private final Path walDir;

    public MetricBufferQueue(String walDirPath) {
        this.walDir = Paths.get(walDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(walDir);
        } catch (IOException e) {
            log.error("[WAL] 创建目录失败: {}", e.getMessage());
        }
        recoverFromWal();
    }

    public void enqueue(String prometheusText) {
        if (prometheusText == null || prometheusText.isBlank()) return;
        while (queueSize.get() >= MAX_QUEUE_SIZE) {
            String dropped = memoryQueue.poll();
            if (dropped != null) {
                queueSize.decrementAndGet();
                totalDropped.incrementAndGet();
            } else break;
        }
        memoryQueue.offer(prometheusText);
        queueSize.incrementAndGet();
        totalEnqueued.incrementAndGet();
    }

    public List<String> dequeueBatch(int maxSize) {
        int batchSize = Math.min(maxSize, MAX_BATCH_SIZE);
        List<String> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            String item = memoryQueue.poll();
            if (item == null) break;
            queueSize.decrementAndGet();
            batch.add(item);
        }
        return batch;
    }

    public void requeueAndPersist(List<String> failedBatch) {
        for (int i = failedBatch.size() - 1; i >= 0; i--) {
            memoryQueue.offer(failedBatch.get(i));
            queueSize.incrementAndGet();
        }
        persistToWal(failedBatch);
    }

    public void markFlushed(int count) {
        totalFlushed.addAndGet(count);
    }

    // ==================== WAL 持久化 ====================

    private void persistToWal(List<String> data) {
        // P1-6: 写入前检查 WAL 总大小，超限则清理
        enforceWalSizeLimit();

        Path walFile = walDir.resolve("wal-" + System.currentTimeMillis() + ".wal");
        try {
            StringBuilder sb = new StringBuilder();
            for (String item : data) {
                sb.append(item);
                sb.append("---WAL_SEPARATOR---\n");
            }
            Files.writeString(walFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("[WAL] 已持久化 {} 条到 {}", data.size(), walFile.getFileName());
        } catch (IOException e) {
            log.error("[WAL] 持久化失败: {}", e.getMessage());
        }
    }

    /**
     * P1-6: 强制 WAL 总大小不超过 MAX_WAL_SIZE_MB
     */
    private void enforceWalSizeLimit() {
        try {
            if (!Files.isDirectory(walDir)) return;
            long totalBytes = 0;
            List<Path> walFiles = new ArrayList<>();
            try (var files = Files.list(walDir)) {
                files.filter(f -> f.toString().endsWith(".wal"))
                        .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                        .forEach(f -> {
                            walFiles.add(f);
                        });
            }
            for (Path f : walFiles) {
                totalBytes += Files.size(f);
            }
            long limitBytes = MAX_WAL_SIZE_MB * 1024 * 1024;
            // 删除最旧的文件直到低于限制
            int idx = 0;
            while (totalBytes > limitBytes && idx < walFiles.size()) {
                Path oldest = walFiles.get(idx);
                long fileSize = Files.size(oldest);
                Files.delete(oldest);
                totalBytes -= fileSize;
                idx++;
                log.warn("[WAL] 大小超限({}MB)，删除旧文件: {}", MAX_WAL_SIZE_MB, oldest.getFileName());
            }
        } catch (IOException e) {
            log.debug("[WAL] 大小检查异常: {}", e.getMessage());
        }
    }

    private void recoverFromWal() {
        try {
            if (!Files.isDirectory(walDir)) return;
            try (var files = Files.list(walDir)) {
                var walFiles = files.filter(p -> p.toString().endsWith(".wal"))
                        .sorted().toList();
                // v2.30: WAL 堆积告警
                if (walFiles.size() > 50) {
                    log.warn("[WAL] 发现 {} 个积压 WAL 文件，建议检查 RemoteWrite 目标可达性", walFiles.size());
                }
                java.util.concurrent.atomic.AtomicInteger totalRecovered = new java.util.concurrent.atomic.AtomicInteger(0);
                walFiles.forEach(walFile -> {
                    try {
                        String content = Files.readString(walFile, StandardCharsets.UTF_8);
                        String[] parts = content.split("---WAL_SEPARATOR---\n");
                        int recovered = 0;
                        for (String part : parts) {
                            if (!part.isBlank()) {
                                memoryQueue.offer(part.trim());
                                queueSize.incrementAndGet();
                                recovered++;
                            }
                        }
                        Files.delete(walFile);
                        if (recovered > 0) {
                            totalRecovered.addAndGet(recovered);
                            log.debug("[WAL] 从 {} 恢复 {} 条", walFile.getFileName(), recovered);
                        }
                    } catch (IOException e) {
                        log.warn("[WAL] 恢复失败: {}", walFile.getFileName());
                    }
                });
                if (totalRecovered.get() > 0 || walFiles.size() > 0) {
                    log.info("[WAL] 已初始化, 队列恢复 {} 条 ({} 个文件)", totalRecovered.get(), walFiles.size());
                }
            }
        } catch (IOException e) {
            log.debug("[WAL] 恢复扫描异常: {}", e.getMessage());
        }
    }

    public void cleanWal() {
        try {
            if (!Files.isDirectory(walDir)) return;
            try (var files = Files.list(walDir)) {
                files.filter(p -> p.toString().endsWith(".wal"))
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }

    public int size() { return queueSize.get(); }
    public long getTotalEnqueued() { return totalEnqueued.get(); }
    public long getTotalDropped() { return totalDropped.get(); }
    public long getTotalFlushed() { return totalFlushed.get(); }
    public boolean isEmpty() { return memoryQueue.isEmpty(); }
}