package com.opsmonitor.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.ExporterInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Prometheus File Service Discovery — targets JSON 文件管理
 *
 * 9G-1 架构审计修复：
 * - 原子写入：先写 .tmp 文件再 rename，避免 Prometheus 读取半文件
 * - ReentrantLock：防止并发写入冲突
 * - 完整 labels：project / service / env / server_id / server_name / exporter_type / managed_by
 *
 * 文件结构：docker/targets/{type}.json
 * Prometheus 通过 file_sd_configs 按 type 拆分为独立 job
 */
@Slf4j
@Component
public class PrometheusTargetWriter {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;

    /** 9G-1: 文件写入锁，防止并发写入导致文件损坏 */
    private final ReentrantLock writeLock = new ReentrantLock();

    public PrometheusTargetWriter(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path getTargetsDir() {
        return Paths.get(properties.getCompose().getWorkDir(), "targets").toAbsolutePath().normalize();
    }

    /**
     * N25修复：Exporter type 文件名安全校验
     *
     * type 参数直接拼入文件路径（type+".json"），若不校验可导致路径穿越：
     * type="../data/users" → 写入 targets/../data/users.json
     *
     * 修复策略：
     * 1. type 格式白名单：仅允许 [a-z0-9_-]，1-32位
     * 2. 解析后路径必须在 targetsDir 内
     */
    private static final java.util.regex.Pattern TYPE_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_-]{0,31}$");

    private String sanitizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Exporter type 不能为空");
        }
        String lower = type.toLowerCase().trim();
        if (!TYPE_NAME_PATTERN.matcher(lower).matches()) {
            throw new IllegalArgumentException("Exporter type 格式不合法: " + type
                    + " (仅允许小写字母/数字/下划线/中划线，1-32位)");
        }
        // 额外边界检查
        Path resolved = getTargetsDir().resolve(lower + ".json").normalize();
        if (!resolved.startsWith(getTargetsDir())) {
            throw new IllegalArgumentException("Exporter type 路径越界: " + type);
        }
        return lower;
    }

    /**
     * 根据所有 Exporter 实例重新生成全部 targets 文件
     * 按 type 分组写入不同文件
     */
    public void writeAllTargets(Collection<ExporterInstance> exporters) {
        writeLock.lock();
        try {
            Path targetsDir = getTargetsDir();
            Files.createDirectories(targetsDir);

            Map<String, List<ExporterInstance>> byType = exporters.stream()
                    .collect(Collectors.groupingBy(ExporterInstance::getType));

            Set<String> writtenTypes = new HashSet<>();
            for (Map.Entry<String, List<ExporterInstance>> entry : byType.entrySet()) {
                try {
                    String safeType = sanitizeType(entry.getKey());
                    writeTargetFileAtomic(safeType, entry.getValue());
                    writtenTypes.add(safeType);
                } catch (IllegalArgumentException e) {
                    log.warn("[TargetWriter] 跳过非法 type: {} — {}", entry.getKey(), e.getMessage());
                }
            }

            cleanupStaleFiles(targetsDir, writtenTypes);
            // v2.30: 0 个类型时不打 info
            if (!writtenTypes.isEmpty()) {
                log.info("Prometheus targets 文件已更新（原子写入），共 {} 个类型", writtenTypes.size());
            }
        } catch (IOException e) {
            log.error("写入 Prometheus targets 失败: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 9G-1: 原子写入单个 type 的 targets 文件
     * 流程：写入 .tmp → rename → 完成
     * 9G-2: 完整 labels 体系
     */
    private void writeTargetFileAtomic(String type, List<ExporterInstance> instances) throws IOException {
        // N25修复：type 已在调用处经过 sanitizeType() 校验，此处再次确认路径在边界内
        Path targetFile = getTargetsDir().resolve(type + ".json").normalize();
        Path tmpFile = getTargetsDir().resolve(type + ".json.tmp").normalize();
        if (!targetFile.startsWith(getTargetsDir())) {
            throw new IllegalArgumentException("targets 路径越界: " + type);
        }

        List<Map<String, Object>> sdConfigs = new ArrayList<>();
        for (ExporterInstance inst : instances) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("targets", List.of(inst.getScrapeTarget()));

            // 9G-2: 完整标签体系
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("server_id", inst.getServerId() != null ? inst.getServerId() : "local");
            // 修复：使用 serverName（服务器名称），而非 displayName（Exporter类型名如"Redis Exporter"）
            // displayName 只是 Exporter 的显示名，与服务器名无关
            String srvName = inst.getServerName();
            if (srvName == null || srvName.isBlank()) {
                srvName = inst.getServerId() != null ? inst.getServerId() : "unknown";
            }
            labels.put("server_name", srvName);
            labels.put("exporter_type", inst.getType());
            labels.put("managed_by", "ops-monitor");
            labels.put("exporter_id", inst.getId());
            // server 地址标签（用于拓扑）
            if (inst.getTargetAddress() != null && !inst.getTargetAddress().isBlank()) {
                labels.put("server", extractHost(inst.getTargetAddress()));
            }
            // project / service / env 标签（Dashboard 变量依赖）
            if (inst.getProject() != null && !inst.getProject().isBlank()) {
                labels.put("project", inst.getProject());
            }
            if (inst.getService() != null && !inst.getService().isBlank()) {
                labels.put("service", inst.getService());
            }
            if (inst.getEnvironment() != null && !inst.getEnvironment().isBlank()) {
                labels.put("env", inst.getEnvironment());
            }
            // node 标签 = server_id (简化拓扑查询)
            labels.put("node", inst.getServerId() != null ? inst.getServerId() : "local");
            entry.put("labels", labels);

            sdConfigs.add(entry);
        }

        // Hotfix-4: 原子写入 Windows 兼容
        // 优先 ATOMIC_MOVE，失败则 fallback 到 REPLACE_EXISTING（Windows 可能不支持 ATOMIC_MOVE）
        mapper.writeValue(tmpFile.toFile(), sdConfigs);
        try {
            Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Windows fallback: copy + delete
            log.debug("ATOMIC_MOVE 不支持，使用 fallback: {}", targetFile.getFileName());
            Files.copy(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(tmpFile);
        }
        log.debug("原子写入 targets 文件: {} ({} 个目标)", targetFile.getFileName(), instances.size());
    }

    /**
     * 从地址中提取 host 部分
     */
    private String extractHost(String address) {
        String host = address;
        if (host.contains("://")) host = host.substring(host.indexOf("://") + 3);
        if (host.contains("/")) host = host.substring(0, host.indexOf("/"));
        if (host.contains(":")) host = host.substring(0, host.indexOf(":"));
        return host;
    }

    private void cleanupStaleFiles(Path targetsDir, Set<String> activeTypes) {
        try {
            if (!Files.isDirectory(targetsDir)) return;
            java.util.concurrent.atomic.AtomicInteger cleanedCount = new java.util.concurrent.atomic.AtomicInteger(0);
            Files.list(targetsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.toString().endsWith(".tmp"))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString().replace(".json", "");
                        if (!activeTypes.contains(fileName)) {
                            try {
                                Path tmpP = p.resolveSibling(p.getFileName() + ".tmp");
                                mapper.writeValue(tmpP.toFile(), List.of());
                                try {
                                    java.nio.file.Files.move(tmpP, p,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                                } catch (java.nio.file.AtomicMoveNotSupportedException e2) {
                                    java.nio.file.Files.move(tmpP, p,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                                // v2.30: 降级为 debug，汇总在下方
                                log.debug("清空过期 targets 文件: {}", p.getFileName());
                                cleanedCount.incrementAndGet();
                            } catch (IOException e) {
                                log.warn("清空 targets 文件失败: {}", e.getMessage());
                            }
                        }
                    });
            // v2.30: 汇总日志
            if (cleanedCount.get() > 0) {
                log.info("清空 {} 个过期 targets 文件", cleanedCount.get());
            }
        } catch (IOException e) {
            log.debug("清理 targets 目录异常: {}", e.getMessage());
        }
    }

    public void ensureTargetsDir() {
        try {
            Files.createDirectories(getTargetsDir());
        } catch (IOException e) {
            log.error("创建 targets 目录失败: {}", e.getMessage());
        }
    }
}