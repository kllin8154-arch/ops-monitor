package com.opsmonitor.sentinel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Incident 管理服务 — v2.11 加固版
 *
 * FIX-INC-1: 内存条目总上限 MAX_STORE_SIZE=1000，活跃上限 MAX_ACTIVE_SIZE=500
 * FIX-INC-2: CLOSED Incident 超过阈值自动按月归档到 incidents-archive-YYYY-MM.json
 * FIX-INC-3: 启动时只加载活跃 + 90天内近期条目，避免全量加载 OOM
 * FIX-INC-4: findById 内存未命中时查归档文件（按需读取，不常驻内存）
 *
 * v2.11 P1-Dup: open() 同时检查 OPEN+INVESTIGATING 状态防止同指纹重复创建
 * v2.11 DIRTY:  loadActiveFromFile() 增加 JSON 结构校验 + 字段完整性修复
 *               自动将残留 .tmp 文件清理，防止脏写污染
 */
@Slf4j
@Service
public class IncidentService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper         mapper;
    private final Map<String, Incident> store    = new ConcurrentHashMap<>();
    private final ReentrantLock         fileLock = new ReentrantLock();

    // FIX-INC-1: 上限常量
    private static final int MAX_ACTIVE_SIZE  = 500;   // OPEN+INVESTIGATING 上限
    private static final int MAX_STORE_SIZE   = 1000;  // 主 store 总上限
    private static final int ARCHIVE_TRIGGER  = 200;   // CLOSED 超过此数触发归档
    private static final int ARCHIVE_KEEP     = 50;    // 归档后保留在 store 中的最新 CLOSED 条数
    private static final long RECENT_DAYS     = 90L;   // 启动时加载近期条目的天数

    public IncidentService(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        loadActiveFromFile(); // FIX-INC-3
    }

    // ── CRUD ───────────────────────────────────────────────────

    public Incident open(String serverId, String serverName, String fingerprint,
                         String faultName, String severity, double impactScore,
                         double confidence, String rootCause,
                         List<RunbookStep> runbookSteps) {

        // v2.11 P1-Dup: 同 serverId + fingerprint，OPEN 或 INVESTIGATING 均视为已存在
        // 原实现只检查 OPEN，导致 INVESTIGATING 中的事件被重复创建
        Optional<Incident> existing = store.values().stream()
                .filter(i -> serverId.equals(i.getServerId())
                        && fingerprint.equals(i.getFingerprint())
                        && ("OPEN".equals(i.getStatus()) || "INVESTIGATING".equals(i.getStatus())))
                .findFirst();
        if (existing.isPresent()) {
            Incident dup = existing.get();
            // v2.12: 更新置信度、根因、impactScore（用最新诊断信息覆盖，但不改变状态）
            dup.setConfidence(confidence);
            dup.setRootCause(rootCause);
            dup.setImpactScore(impactScore);
            // v2.12: 记录最后一次诊断触发时间（运维据此判断告警是否仍活跃）
            dup.setLastSeenTime(System.currentTimeMillis());
            store.put(dup.getId(), dup);
            saveToFile();
            log.info("[IncidentService] P1-Dup: 已存在活跃 Incident [{}]，更新诊断信息(lastSeen=now): {}",
                    dup.getStatus(), dup.getId());
            return dup;
        }

        // FIX-INC-1: 活跃 Incident 上限保护
        long activeCount = store.values().stream()
                .filter(i -> "OPEN".equals(i.getStatus()) || "INVESTIGATING".equals(i.getStatus()))
                .count();
        if (activeCount >= MAX_ACTIVE_SIZE) {
            log.error("[IncidentService] 活跃 Incident 已达上限 {}，拒绝创建。请先处理现有事件。",
                    MAX_ACTIVE_SIZE);
            throw new IllegalStateException(
                    "活跃 Incident 数量已达上限（" + MAX_ACTIVE_SIZE + "），请先处理现有事件");
        }

        Incident incident = Incident.builder()
                .id(UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .serverId(serverId)
                .serverName(serverName)
                .fingerprint(fingerprint)
                .faultName(faultName)
                .severity(severity)
                .status("OPEN")
                .impactScore(impactScore)
                .confidence(confidence)
                .rootCause(rootCause)
                .runbookSteps(runbookSteps != null ? new ArrayList<>(runbookSteps) : new ArrayList<>())
                .executionHistory(new ArrayList<>())
                .startTime(System.currentTimeMillis())
                .lastSeenTime(System.currentTimeMillis()) // v2.12: 初始 lastSeenTime = startTime
                .build();

        store.put(incident.getId(), incident);
        saveToFile();
        log.info("[IncidentService] 新 Incident: {} [{}] {} - {}",
                incident.getId(), severity, serverName, faultName);
        return incident;
    }

    /**
     * v2.12: 带指标快照和受影响 Exporter 的重载 open 方法
     * DiagnosisEngine → AlertCenterService 调用此版本，传入完整诊断上下文。
     * 去重场景同样更新快照和 Exporter 列表。
     */
    public Incident open(String serverId, String serverName, String fingerprint,
                         String faultName, String severity, double impactScore,
                         double confidence, String rootCause,
                         List<RunbookStep> runbookSteps,
                         Map<String, Double> indicatorSnapshot,
                         List<String> affectedExporters) {

        Incident result = open(serverId, serverName, fingerprint, faultName,
                severity, impactScore, confidence, rootCause, runbookSteps);
        // 补充 v2.12 字段（对新建和去重复用的 Incident 均生效）
        if (indicatorSnapshot != null) {
            result.setIndicatorSnapshot(new java.util.LinkedHashMap<>(indicatorSnapshot));
        }
        if (affectedExporters != null) {
            result.setAffectedExporters(new ArrayList<>(affectedExporters));
        }
        store.put(result.getId(), result);
        saveToFile();
        return result;
    }

    public Incident investigate(String id, String operator) {
        Incident i = getOrThrow(id);
        i.setStatus("INVESTIGATING");
        i.setOperator(operator);
        store.put(id, i);
        saveToFile();
        log.info("[IncidentService] {} → INVESTIGATING (operator={})", id, operator);
        return i;
    }

    public Incident recordExecution(String id, List<ExecutionResult> results) {
        Incident i = getOrThrow(id);
        if (i.getExecutionHistory() == null) i.setExecutionHistory(new ArrayList<>());
        i.getExecutionHistory().addAll(results);
        if ("OPEN".equals(i.getStatus())) i.setStatus("INVESTIGATING");
        store.put(id, i);
        saveToFile();
        log.info("[IncidentService] {} 执行记录更新，共 {} 步", id, results.size());
        return i;
    }

    public Incident resolve(String id, String notes) {
        Incident i = getOrThrow(id);
        i.setStatus("RESOLVED");
        i.setEndTime(System.currentTimeMillis());
        if (notes != null && !notes.isBlank()) i.setNotes(notes);
        store.put(id, i);
        saveToFile();
        log.info("[IncidentService] {} → RESOLVED (duration={}min)",
                id, i.getDurationMs() / 60000);
        return i;
    }

    /**
     * v2.11 FIX-BRIDGE-1: 按 serverId + fingerprint 批量 RESOLVED 活跃 Incident
     * 供 ReachabilityIncidentBridge 在主机恢复时调用，无需知道具体 Incident ID。
     *
     * @return true 如果至少 RESOLVED 了一条
     */
    public boolean resolveByFingerprint(String serverId, String fingerprint, String notes) {
        List<Incident> toResolve = store.values().stream()
                .filter(i -> serverId.equals(i.getServerId())
                        && fingerprint.equals(i.getFingerprint())
                        && ("OPEN".equals(i.getStatus()) || "INVESTIGATING".equals(i.getStatus())))
                .collect(Collectors.toList());

        if (toResolve.isEmpty()) return false;

        long now = System.currentTimeMillis();
        for (Incident i : toResolve) {
            i.setStatus("RESOLVED");
            i.setEndTime(now);
            if (notes != null && !notes.isBlank()) i.setNotes(notes);
            store.put(i.getId(), i);
            log.info("[IncidentService] {} → RESOLVED by fingerprint ({}) duration={}min",
                    i.getId(), fingerprint, i.getDurationMs() / 60000);
        }
        saveToFile();
        return true;
    }

    public Incident close(String id) {
        Incident i = getOrThrow(id);
        i.setStatus("CLOSED");
        if (i.getEndTime() == 0) i.setEndTime(System.currentTimeMillis());
        store.put(id, i);
        saveToFile();
        // FIX-INC-2: 关闭后异步检查归档
        triggerArchiveIfNeeded();
        return i;
    }

    // ── 查询 ───────────────────────────────────────────────────

    public List<Incident> listAll() {
        return store.values().stream()
                .sorted(Comparator.comparingLong(Incident::getStartTime).reversed())
                .collect(Collectors.toList());
    }

    public List<Incident> listOpen() {
        return store.values().stream()
                .filter(i -> "OPEN".equals(i.getStatus()) || "INVESTIGATING".equals(i.getStatus()))
                .sorted(Comparator.comparingDouble(Incident::getImpactScore).reversed())
                .collect(Collectors.toList());
    }

    /** FIX-INC-4: 内存未命中时查归档 */
    public Optional<Incident> findById(String id) {
        Incident active = store.get(id);
        if (active != null) return Optional.of(active);
        return findInArchives(id);
    }

    public Incident getOrThrow(String id) {
        return findById(id).orElseThrow(
                () -> new IllegalArgumentException("Incident 不存在: " + id));
    }

    // ── FIX-INC-2: 归档逻辑 ───────────────────────────────────

    private void triggerArchiveIfNeeded() {
        List<Incident> closed = store.values().stream()
                .filter(i -> "CLOSED".equals(i.getStatus()))
                .sorted(Comparator.comparingLong(Incident::getEndTime))
                .collect(Collectors.toList());

        if (closed.size() < ARCHIVE_TRIGGER) return;

        int toArchive = closed.size() - ARCHIVE_KEEP;
        if (toArchive <= 0) return;

        List<Incident> archiveBatch = closed.subList(0, toArchive);
        log.info("[IncidentService] 触发归档：CLOSED={} 超过阈值 {}，归档 {} 条",
                closed.size(), ARCHIVE_TRIGGER, toArchive);

        Map<String, List<Incident>> byMonth = archiveBatch.stream()
                .collect(Collectors.groupingBy(i -> {
                    long ts = i.getEndTime() > 0 ? i.getEndTime() : i.getStartTime();
                    return LocalDate.ofEpochDay(ts / 86_400_000L)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM"));
                }));

        fileLock.lock();
        try {
            for (Map.Entry<String, List<Incident>> e : byMonth.entrySet()) {
                appendToArchiveFile(e.getKey(), e.getValue());
                e.getValue().forEach(i -> store.remove(i.getId()));
            }
            saveToFile();
            log.info("[IncidentService] 归档完成，主 store 剩余 {} 条", store.size());
        } finally {
            fileLock.unlock();
        }
    }

    private void appendToArchiveFile(String month, List<Incident> batch) {
        Path archiveFile = getArchiveFile(month);
        try {
            List<Incident> existing = new ArrayList<>();
            if (Files.exists(archiveFile)) {
                try {
                    existing = mapper.readValue(archiveFile.toFile(), new TypeReference<>() {});
                } catch (IOException e) {
                    log.warn("[IncidentService] 读取归档文件出错，将覆盖: {}", e.getMessage());
                }
            }
            existing.addAll(batch);

            Path tmp = archiveFile.resolveSibling(archiveFile.getFileName() + ".tmp");
            Files.createDirectories(archiveFile.getParent());
            mapper.writeValue(tmp.toFile(), existing);
            try {
                Files.move(tmp, archiveFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, archiveFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[IncidentService] 归档文件更新: {} ({} 条)", archiveFile.getFileName(), existing.size());
        } catch (IOException e) {
            log.error("[IncidentService] 归档写入失败 month={}: {}", month, e.getMessage());
        }
    }

    private Optional<Incident> findInArchives(String id) {
        Path dataDir = getDataDir();
        if (!Files.exists(dataDir)) return Optional.empty();
        try {
            List<Path> archives;
            try (var stream = Files.list(dataDir)) {
                archives = stream
                        .filter(p -> p.getFileName().toString().startsWith("incidents-archive-"))
                        .sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList());
            }
            for (Path archive : archives) {
                try {
                    List<Incident> list = mapper.readValue(archive.toFile(), new TypeReference<>() {});
                    Optional<Incident> found = list.stream()
                            .filter(i -> id.equals(i.getId()))
                            .findFirst();
                    if (found.isPresent()) return found;
                } catch (IOException e) {
                    log.debug("[IncidentService] 读取归档 {} 失败: {}", archive.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("[IncidentService] 遍历归档目录失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    // ── 持久化 ─────────────────────────────────────────────────

    private Path getDataDir() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data").normalize();
    }

    private Path getDataFile() {
        return getDataDir().resolve("incidents.json");
    }

    private Path getArchiveFile(String month) {
        return getDataDir().resolve("incidents-archive-" + month + ".json");
    }

    private void saveToFile() {
        fileLock.lock();
        try {
            Path path = getDataFile();
            Path tmp  = path.resolveSibling("incidents.json.tmp");
            Files.createDirectories(path.getParent());
            mapper.writeValue(tmp.toFile(), new ArrayList<>(store.values()));
            try {
                Files.move(tmp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[IncidentService] 保存失败: {}", e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * FIX-INC-3 + v2.11 DIRTY: 启动时加载并校验 incidents.json
     *
     * 脏数据防护措施：
     * 1. 清理残留 .tmp 文件（上次写入崩溃留下的半成品）
     * 2. JSON 结构校验：必须是合法数组，否则备份并重建空文件
     * 3. 字段完整性修复：status/id 为 null 的条目自动跳过并记录警告
     * 4. 只加载活跃 + 90 天内条目，避免全量加载 OOM
     */
    private void loadActiveFromFile() {
        Path dataDir  = getDataDir();
        Path path     = getDataFile();
        Path tmpPath  = path.resolveSibling("incidents.json.tmp");

        // v2.11 DIRTY-1: 清理残留 .tmp 脏文件
        try {
            if (Files.exists(tmpPath)) {
                Files.delete(tmpPath);
                log.warn("[IncidentService] 启动时发现并清理残留脏文件: {}", tmpPath.getFileName());
            }
        } catch (IOException e) {
            log.warn("[IncidentService] 清理 .tmp 文件失败（可忽略）: {}", e.getMessage());
        }

        if (!Files.exists(path)) {
            log.info("[IncidentService] incidents.json 不存在，初始化空列表");
            return;
        }

        try {
            byte[] raw = Files.readAllBytes(path);

            // v2.11 DIRTY-2: JSON 结构校验 — 空文件 or 非数组均视为损坏
            if (raw.length == 0) {
                log.warn("[IncidentService] incidents.json 为空文件，初始化为空列表");
                return;
            }
            JsonNode root;
            try {
                root = mapper.readTree(raw);
            } catch (IOException parseEx) {
                backupCorruptFile(path, "parse-error");
                log.error("[IncidentService] incidents.json JSON 解析失败，已备份原文件，重新初始化: {}",
                        parseEx.getMessage());
                return;
            }
            if (!root.isArray()) {
                backupCorruptFile(path, "not-array");
                log.error("[IncidentService] incidents.json 不是 JSON 数组（类型={}），已备份，重新初始化",
                        root.getNodeType());
                return;
            }

            List<Incident> all = mapper.readValue(raw, new TypeReference<>() {});
            long cutoffMs = System.currentTimeMillis() - RECENT_DAYS * 24 * 60 * 60 * 1000L;
            int loaded = 0;
            int skipped = 0;

            for (Incident i : all) {
                // v2.11 DIRTY-3: 字段完整性校验 — id/status 为 null 跳过
                if (i.getId() == null || i.getId().isBlank()) {
                    log.warn("[IncidentService] 跳过 id 为空的脏数据条目（faultName={}）", i.getFaultName());
                    skipped++;
                    continue;
                }
                if (i.getStatus() == null) {
                    log.warn("[IncidentService] 跳过 status 为 null 的脏数据条目 id={}", i.getId());
                    skipped++;
                    continue;
                }

                boolean isActive = "OPEN".equals(i.getStatus()) || "INVESTIGATING".equals(i.getStatus());
                boolean isRecent = i.getStartTime() > cutoffMs;
                if (isActive || isRecent) {
                    store.put(i.getId(), i);
                    loaded++;
                }
            }

            log.info("[IncidentService] 加载 {}/{} 个 Incident（活跃或{}天内），跳过脏数据 {} 条",
                    loaded, all.size(), RECENT_DAYS, skipped);

            if (all.size() > MAX_STORE_SIZE) {
                log.warn("[IncidentService] incidents.json 共 {} 条，超过建议上限 {}，建议执行归档清理",
                        all.size(), MAX_STORE_SIZE);
            }
        } catch (IOException e) {
            log.error("[IncidentService] 加载 incidents.json 失败: {}", e.getMessage());
        }
    }

    /**
     * v2.11 DIRTY: 将损坏的数据文件备份，避免数据完全丢失
     * 备份路径：incidents.json.corrupt-{reason}-{timestamp}
     */
    private void backupCorruptFile(Path path, String reason) {
        try {
            String backupName = "incidents.json.corrupt-" + reason + "-" + System.currentTimeMillis();
            Path backup = path.resolveSibling(backupName);
            Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // 写入空数组，确保系统能正常启动
            Files.writeString(path, "[]");
            log.warn("[IncidentService] 损坏文件已备份至: {}，原文件重置为空数组", backupName);
        } catch (IOException e) {
            log.error("[IncidentService] 备份损坏文件失败: {}", e.getMessage());
        }
    }
}