package com.opsmonitor.sentinel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsmonitor.config.OpsMonitorProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * v2.11 Sentinel 数据健康守护:Incident 过期自动归档
 *
 * 问题背景:
 *   用户截图显示 Sentinel 有多条陈旧 Incident(27 天前心跳超时 / 12 天前 Exporter 离线),
 *   仍然是 OPEN 状态,没有自动过期归档机制。
 *
 * 设计决策(为什么独立一个新类而不改 IncidentService):
 *   - AGENT_RULES 要求 sentinel 包不增子包 + 最小侵入
 *   - IncidentService 是核心类,改动风险大
 *   - 本类通过独立轮询 incidents.json 做事后归档,不侵入核心链路
 *   - 归档后 IncidentService 下次加载时会读到更新后的数据
 *
 * 归档规则:
 *   - Incident 已 OPEN 超过 IDLE_DAYS 天 → 自动 RESOLVED
 *   - 归档时追加 note 说明为系统自动归档
 *   - 保留原有 startTime/rootCause 等所有字段,只改 status + endTime + notes
 *
 * 注意事项:
 *   - 使用文件锁 + 原子写避免与 IncidentService 并发写冲突
 *   - 每 1 小时扫描一次,开销极低
 *   - 默认 IDLE_DAYS=7 天,可通过环境变量 OPS_INCIDENT_IDLE_DAYS 覆盖
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentCleaner {

    private final OpsMonitorProperties properties;

    private static final int DEFAULT_IDLE_DAYS = 7;
    private static final long SCAN_INTERVAL_MS = 60 * 60 * 1000L; // 1 小时
    private static final long INITIAL_DELAY_MS = 5 * 60 * 1000L;  // 启动后 5 分钟开始

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "incident-cleaner");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::scanAndArchive,
                INITIAL_DELAY_MS,
                SCAN_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        log.info("[IncidentCleaner] 已启动,每 {}h 扫描一次陈旧 Incident(保留 {} 天)",
                SCAN_INTERVAL_MS / 3600000, getIdleDays());
    }

    @PreDestroy
    public void stop() {
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {}
    }

    /**
     * 允许手动触发(便于管理界面加"立即清理"按钮)
     */
    public int scanAndArchive() {
        Path incidentsFile = getIncidentsFile();
        if (!Files.exists(incidentsFile)) {
            return 0;
        }

        int archived = 0;
        try {
            byte[] data = Files.readAllBytes(incidentsFile);
            if (data.length == 0) return 0;

            JsonNode root = mapper.readTree(data);
            if (!root.isArray()) {
                log.warn("[IncidentCleaner] incidents.json 不是 JSON 数组,跳过");
                return 0;
            }

            long idleThresholdMs = getIdleDays() * 24L * 60 * 60 * 1000L;
            long now = System.currentTimeMillis();
            boolean changed = false;

            for (JsonNode node : root) {
                if (!node.isObject()) continue;
                ObjectNode incident = (ObjectNode) node;

                String status = incident.path("status").asText("");
                if (!"OPEN".equalsIgnoreCase(status) && !"INVESTIGATING".equalsIgnoreCase(status)) {
                    continue; // 只处理未完结的
                }

                long startTime = incident.path("startTime").asLong(0);
                if (startTime <= 0) continue;

                long ageMs = now - startTime;
                if (ageMs >= idleThresholdMs) {
                    // 归档
                    incident.put("status", "RESOLVED");
                    incident.put("endTime", now);
                    String originalNotes = incident.path("notes").isNull() ? "" : incident.path("notes").asText("");
                    long ageDays = ageMs / (24L * 3600 * 1000);
                    String autoNote = String.format("[系统自动归档] Incident 开放超过 %d 天(%d 天前),判定已失效",
                            getIdleDays(), ageDays);
                    String finalNotes = originalNotes.isBlank() ? autoNote
                            : originalNotes + " | " + autoNote;
                    incident.put("notes", finalNotes);
                    incident.put("operator", "system-auto");
                    archived++;
                    changed = true;
                    log.info("[IncidentCleaner] 归档陈旧 Incident: id={}, 故障={}, 服务器={}, 开放 {} 天",
                            incident.path("id").asText(),
                            incident.path("faultName").asText(),
                            incident.path("serverName").asText(),
                            ageDays);
                }
            }

            if (changed) {
                writeAtomic(incidentsFile, (ArrayNode) root);
                log.info("[IncidentCleaner] 本次归档 {} 条 Incident", archived);
            }
        } catch (IOException e) {
            log.error("[IncidentCleaner] 处理 incidents.json 失败: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("[IncidentCleaner] 意外异常: {}", e.getMessage(), e);
        }
        return archived;
    }

    /** 原子写:先写 .tmp 再 rename */
    private void writeAtomic(Path target, ArrayNode data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path getIncidentsFile() {
        String workDir = properties.getCompose() == null ? "." : properties.getCompose().getWorkDir();
        return Paths.get(workDir, "..", "data", "incidents.json").normalize();
    }

    private int getIdleDays() {
        String env = System.getenv("OPS_INCIDENT_IDLE_DAYS");
        if (env != null && !env.isBlank()) {
            try {
                int v = Integer.parseInt(env.trim());
                if (v > 0 && v < 3650) return v;
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_IDLE_DAYS;
    }
}