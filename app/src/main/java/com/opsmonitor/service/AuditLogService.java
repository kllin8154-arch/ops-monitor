package com.opsmonitor.service;

import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 审计日志服务
 *
 * OPTIMIZED (Phase4 - OPT-6): 清理过期文件逻辑改为定时任务
 *
 * 原实现问题：
 *   cleanOldAuditFiles() 在每条 persistAsync() 中调用，
 *   即每写一条审计日志都会触发一次 Files.newDirectoryStream() 扫描 data 目录。
 *   高频 API 调用时（如批量注册、压测）会产生大量无意义的目录 IO。
 *
 * 优化方案：
 *   将 cleanOldAuditFiles() 从热路径移出，改为 @Scheduled 每天凌晨 2:30 执行一次。
 *   - 节省 99%+ 的无效目录扫描 IO
 *   - 写入路径 persistAsync() 完全不再触碰文件系统扫描
 *   - 凌晨低峰期执行清理，不影响日间业务
 *
 * 注意：需在 Spring Boot 主类或配置类上添加 @EnableScheduling
 */
@Slf4j
@Service
public class AuditLogService {

    private final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();
    private static final int MAX_ENTRIES = 500;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ReentrantLock fileLock = new ReentrantLock();

    @Value("${ops-monitor.compose.work-dir:docker}")
    private String workDir;

    // OPTIMIZED: 专用单线程池（daemon，不阻止 JVM 关闭）
    private static final java.util.concurrent.ExecutorService AUDIT_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "audit-persist");
                t.setDaemon(true);
                return t;
            });

    /**
     * 记录审计日志
     */
    public void log(String operator, String action, String target, String result) {
        AuditEntry entry = AuditEntry.builder()
                .operator(operator != null ? operator : "anonymous")
                .action(action)
                .target(target)
                .result(result)
                .timestamp(System.currentTimeMillis())
                .build();

        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }

        log.info("[AUDIT] {} | {} | {} | {}", entry.getOperator(), action, target, result);
        // OPTIMIZED: 只做写入，不再在热路径触发目录扫描
        persistAsync(entry);
    }

    /**
     * OPTIMIZED: 异步写入，移除了原来的 cleanOldAuditFiles() 调用
     * 清理逻辑已移至 @Scheduled 定时任务 scheduledCleanOldAuditFiles()
     */
    private void persistAsync(AuditEntry entry) {
        AUDIT_POOL.submit(() -> {
            fileLock.lock();
            try {
                String today   = LocalDate.now().toString();
                Path dataDir   = Paths.get(workDir, "..", "data").normalize();
                Path auditFile = dataDir.resolve("audit-" + today + ".json");
                Files.createDirectories(dataDir);
                String line = mapper.writeValueAsString(entry) + System.lineSeparator();
                Files.write(auditFile, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // OPTIMIZED: 此处不再调用 cleanOldAuditFiles()
            } catch (IOException e) {
                log.warn("[AuditLog] 持久化失败（不影响业务）: {}", e.getMessage());
            } finally {
                fileLock.unlock();
            }
        });
    }

    /**
     * OPTIMIZED (OPT-6): 过期文件清理改为定时任务
     *
     * 原来：每条日志写入时触发（高频 IO 放大）
     * 现在：每天凌晨 2:30 执行一次（低峰期，减少 99%+ 无效 IO）
     *
     * cron 表达式：秒 分 时 日 月 周
     * "0 30 2 * * ?"  = 每天 02:30:00 执行
     */
    @Scheduled(cron = "0 30 2 * * ?")
    public void scheduledCleanOldAuditFiles() {
        Path dataDir = Paths.get(workDir, "..", "data").normalize();
        if (!Files.exists(dataDir)) return;
        log.info("[AuditLog] 开始清理过期审计文件（保留7天）...");
        int deleted = 0;
        try {
            LocalDate cutoff = LocalDate.now().minusDays(7);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "audit-*.json")) {
                for (Path f : stream) {
                    String name = f.getFileName().toString();
                    // audit-2026-03-01.json → 提取日期部分（第6~16字符）
                    if (name.length() < 16) continue;
                    String datePart = name.substring(6, 16);
                    try {
                        LocalDate fileDate = LocalDate.parse(datePart);
                        if (fileDate.isBefore(cutoff)) {
                            Files.deleteIfExists(f);
                            deleted++;
                            log.info("[AuditLog] 已删除过期审计文件: {}", f.getFileName());
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("[AuditLog] 清理过期文件时发生异常: {}", e.getMessage());
        }
        log.info("[AuditLog] 清理完成，共删除 {} 个过期文件", deleted);
    }

    public void logSuccess(String operator, String action, String target) {
        log(operator, action, target, "SUCCESS");
    }

    public void logFail(String operator, String action, String target, String reason) {
        log(operator, action, target, "FAIL: " + reason);
    }

    public List<AuditEntry> list(int limit) {
        return entries.stream().limit(limit).toList();
    }

    @Data
    @Builder
    public static class AuditEntry {
        private String operator;
        private String action;
        private String target;
        private String result;
        private long   timestamp;

        public String getTimeFormatted() {
            return FORMATTER.format(Instant.ofEpochMilli(timestamp));
        }
    }
    /**
     * v2.10 P1-07 修复:注册线程池关闭钩子,避免 Spring DevTools 热重启/多次 @SpringBootTest 累积 → OOM
     */
    @PreDestroy
    public void shutdownThreadPool_v210() {
        try {
            if (AUDIT_POOL != null && !AUDIT_POOL.isShutdown()) {
                AUDIT_POOL.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
}