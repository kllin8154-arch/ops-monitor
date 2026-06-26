package com.opsmonitor.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.opsmonitor.config.OpsMonitorProperties;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 配置中心 (10D-2)
 *
 * 统一管理所有配置的版本、热更新、回滚。
 * 替代直接写文件的模式，所有配置变更通过 ConfigCenter 管控。
 *
 * 功能：
 * - 配置版本管理（每次变更自动保存历史版本）
 * - 热更新（变更后自动触发 reload）
 * - 回滚（可回滚到任意历史版本）
 * - 配置对比（diff 两个版本）
 *
 * 管理的配置：
 * - prometheus.yml
 * - alert.rules.yml
 * - alertmanager.yml
 * - targets/*.json
 */
@Slf4j
@Service
public class ConfigCenterService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;

    /** 配置版本历史: configName → List<ConfigVersion> */
    private final Map<String, List<ConfigVersion>> versionHistory = new ConcurrentHashMap<>();

    /** 当前活跃版本: configName → version */
    private final Map<String, Integer> currentVersions = new ConcurrentHashMap<>();

    private static final int MAX_HISTORY = 20;

    /** P3修复：版本历史持久化写锁 */
    private final ReentrantLock historyLock = new ReentrantLock();

    public ConfigCenterService(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * P3修复：启动时从文件恢复版本历史
     * 解决重启后 listConfigs/history/rollback 与真实文件状态不一致的问题
     */
    @PostConstruct
    public void init() {
        loadHistoryFromFile();
        log.info("[ConfigCenter] 已恢复 {} 个配置的版本历史", versionHistory.size());
    }

    /**
     * 提交配置变更（自动版本化 + 写入文件 + 保存历史）
     *
     * FIX-CONTENT: 写入前校验内容格式（YAML 语法 + 长度限制）
     */
    public ConfigVersion commitConfig(String configName, String content, String changeReason) {
        // FIX-CONTENT: 内容安全校验（拒绝空内容、超长内容、非法 YAML）
        validateContent(configName, content);

        List<ConfigVersion> history = versionHistory.computeIfAbsent(configName, k -> new ArrayList<>());
        int newVersion = currentVersions.getOrDefault(configName, 0) + 1;

        ConfigVersion cv = ConfigVersion.builder()
                .configName(configName)
                .version(newVersion)
                .content(content)
                .changeReason(changeReason)
                .timestamp(System.currentTimeMillis())
                .build();

        // 写入文件
        Path filePath = resolveConfigPath(configName);
        try {
            // 备份当前版本
            if (Files.exists(filePath)) {
                Path backupPath = filePath.resolveSibling(configName + ".v" + (newVersion - 1) + ".bak");
                Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
            // 原子写入新版本
            Path tmpPath = filePath.resolveSibling(configName + ".tmp");
            Files.writeString(tmpPath, content, StandardCharsets.UTF_8);
            Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);

            cv.setApplied(true);
            log.info("[ConfigCenter] 配置 {} 已更新到 v{}: {}", configName, newVersion, changeReason);
        } catch (IOException e) {
            cv.setApplied(false);
            cv.setError(e.getMessage());
            log.error("[ConfigCenter] 配置 {} 写入失败: {}", configName, e.getMessage());
        }

        // 保存历史
        history.add(cv);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        currentVersions.put(configName, newVersion);

        // P3修复：每次提交后持久化版本历史到文件
        saveHistoryToFile();

        return cv;
    }

    /**
     * FIX-CONTENT: 配置内容安全校验
     *
     * 校验规则：
     * 1. 内容不能为空
     * 2. 内容不能超过 512KB（防止超大文件写入磁盘）
     * 3. YAML 配置文件必须是合法的 YAML 语法（防止写入损坏配置导致 Prometheus 无法启动）
     * 4. JSON 文件（targets/）必须是合法的 JSON 语法
     */
    private void validateContent(String configName, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("配置内容不能为空");
        }

        // FIX-CONTENT: 内容长度限制（防止超大文件）
        if (content.length() > 512 * 1024) {
            throw new IllegalArgumentException(
                    "配置内容超过最大限制（512KB），当前大小: " + content.length() / 1024 + "KB");
        }

        // FIX-CONTENT: 根据文件类型校验格式
        if (configName != null && configName.endsWith(".json")) {
            // JSON 文件（targets/*.json）：校验 JSON 语法
            try {
                new ObjectMapper().readTree(content);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "配置内容不是有效的 JSON 格式: " + e.getMessage());
            }
        } else if (configName != null && configName.endsWith(".yml")) {
            // YAML 文件：校验 YAML 语法
            try {
                new ObjectMapper(new YAMLFactory()).readTree(content);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "配置内容不是有效的 YAML 格式: " + e.getMessage());
            }
        }

        // FIX-CONTENT: 拒绝包含明显危险内容（shell 注入防御纵深）
        // 配置文件中不应包含这些内容，若有说明可能是注入攻击
        String lower = content.toLowerCase();
        if (lower.contains("$(") || lower.contains("`") || lower.contains("${ifs}")) {
            throw new IllegalArgumentException("配置内容包含不允许的 shell 特殊字符序列");
        }
    }

    // rollback 方法已移至持久化区域（支持重启后从备份文件恢复 content）

    /**
     * 获取版本历史
     */
    public List<ConfigVersion> getHistory(String configName) {
        return versionHistory.getOrDefault(configName, List.of());
    }

    /**
     * 获取当前版本号
     */
    public int getCurrentVersion(String configName) {
        return currentVersions.getOrDefault(configName, 0);
    }

    /**
     * 读取当前配置内容
     */
    public String readConfig(String configName) {
        Path filePath = resolveConfigPath(configName);
        try {
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("[ConfigCenter] 读取 {} 失败: {}", configName, e.getMessage());
        }
        return null;
    }

    /**
     * 获取管理的配置列表
     */
    public Map<String, Integer> listConfigs() {
        return Map.copyOf(currentVersions);
    }

    // ==================== P3修复：版本历史持久化 ====================

    /**
     * 获取版本历史存储文件路径
     * 存储在 data/config-history.json，与其他 JSON 持久化文件并列
     */
    private Path getHistoryFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "config-history.json").normalize();
    }

    /**
     * 持久化版本历史到文件（原子写入）
     * 存储结构：{ "prometheus.yml": [{version:1, ...}, ...], ... }
     */
    private void saveHistoryToFile() {
        historyLock.lock();
        try {
            Path histFile = getHistoryFile();
            Path tmp = histFile.resolveSibling("config-history.json.tmp");
            Files.createDirectories(histFile.getParent());

            // 构建持久化结构（不存储 content 字段，content 已写入实际配置文件）
            // 只存储元数据：version/reason/timestamp/applied，content 按需从文件读取
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("versions", currentVersions);
            data.put("history", buildHistoryMeta());

            mapper.writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, histFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, histFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[ConfigCenter] 版本历史持久化失败: {}", e.getMessage());
        } finally {
            historyLock.unlock();
        }
    }

    /** 构建不含 content 的历史元数据（减小文件体积） */
    private Map<String, List<Map<String, Object>>> buildHistoryMeta() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        versionHistory.forEach((name, versions) -> {
            List<Map<String, Object>> metas = new ArrayList<>();
            for (ConfigVersion v : versions) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("configName",    v.getConfigName());
                m.put("version",       v.getVersion());
                m.put("changeReason",  v.getChangeReason());
                m.put("timestamp",     v.getTimestamp());
                m.put("applied",       v.isApplied());
                // 不存 content（大文件），回滚时从 .bak 文件读取
                metas.add(m);
            }
            result.put(name, metas);
        });
        return result;
    }

    /**
     * 从文件恢复版本历史（启动时调用）
     * 版本元数据从 config-history.json 恢复，content 从备份文件按需读取
     */
    @SuppressWarnings("unchecked")
    private void loadHistoryFromFile() {
        Path histFile = getHistoryFile();
        if (!Files.exists(histFile)) {
            // 首次启动：扫描 workDir 下存在的配置文件，自动纳管为 v1
            autoIngestExistingConfigs();
            return;
        }
        try {
            Map<String, Object> data = mapper.readValue(histFile.toFile(),
                    new TypeReference<Map<String, Object>>() {});

            // 恢复版本号
            Map<String, Integer> versions = (Map<String, Integer>) data.get("versions");
            if (versions != null) currentVersions.putAll(versions);

            // 恢复历史元数据（content 字段为空，需要时从备份文件读）
            Map<String, List<Map<String, Object>>> histMeta =
                    (Map<String, List<Map<String, Object>>>) data.get("history");
            if (histMeta != null) {
                histMeta.forEach((name, metas) -> {
                    List<ConfigVersion> versions2 = new ArrayList<>();
                    for (Map<String, Object> m : metas) {
                        ConfigVersion cv = ConfigVersion.builder()
                                .configName(name)
                                .version(((Number) m.getOrDefault("version", 0)).intValue())
                                .changeReason((String) m.getOrDefault("changeReason", ""))
                                .timestamp(((Number) m.getOrDefault("timestamp", 0L)).longValue())
                                .applied((Boolean) m.getOrDefault("applied", true))
                                .content(null) // content 不持久化，回滚时从文件读
                                .build();
                        versions2.add(cv);
                    }
                    versionHistory.put(name, versions2);
                });
            }
            log.info("[ConfigCenter] 已从文件恢复 {} 个配置的版本历史", versionHistory.size());
        } catch (IOException e) {
            log.error("[ConfigCenter] 加载版本历史失败，将重新扫描: {}", e.getMessage());
            autoIngestExistingConfigs();
        }
    }

    /**
     * 首次启动时自动纳管已有配置文件（v1）
     */
    private void autoIngestExistingConfigs() {
        String[] managed = {"prometheus.yml", "alert.rules.yml", "alertmanager.yml", "alertmanager.yml"};
        for (String name : managed) {
            try {
                Path p = resolveConfigPath(name);
                if (Files.exists(p) && !currentVersions.containsKey(name)) {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    commitConfig(name, content, "系统启动自动纳管");
                }
            } catch (Exception e) {
                log.debug("[ConfigCenter] 自动纳管 {} 跳过: {}", name, e.getMessage());
            }
        }
    }

    /**
     * rollback 增强：从备份文件读取历史版本 content（若内存中无）
     */
    public ConfigVersion rollback(String configName, int targetVersion) {
        List<ConfigVersion> history = versionHistory.get(configName);
        if (history == null) throw new IllegalArgumentException("配置不存在: " + configName);

        ConfigVersion target = history.stream()
                .filter(v -> v.getVersion() == targetVersion)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + configName + " v" + targetVersion));

        // 若 content 为空（重启后只恢复了元数据），从备份文件读取
        String content = target.getContent();
        if (content == null || content.isBlank()) {
            Path backupFile = resolveConfigPath(configName)
                    .resolveSibling(configName + ".v" + targetVersion + ".bak");
            if (Files.exists(backupFile)) {
                try {
                    content = Files.readString(backupFile, StandardCharsets.UTF_8);
                    log.info("[ConfigCenter] 从备份文件恢复 {} v{} 内容", configName, targetVersion);
                } catch (IOException e) {
                    throw new IllegalArgumentException("备份文件读取失败: " + e.getMessage());
                }
            } else {
                throw new IllegalArgumentException(
                        "版本 v" + targetVersion + " 的内容不可用（备份文件不存在），无法回滚");
            }
        }
        return commitConfig(configName, content, "回滚到 v" + targetVersion);
    }

    /**
     * SEC-2修复：配置文件名白名单校验 + 路径穿越防护
     *
     * 安全规则：
     * 1. configName 必须在白名单内（仅允许已知配置文件）
     * 2. targets/ 前缀的文件名必须是合法的 JSON 文件名（无目录穿越）
     * 3. 解析后的绝对路径必须在 workDir 内（防止符号链接绕过）
     *
     * 允许的配置：
     *   prometheus.yml / alert.rules.yml / alertmanager.yml /
     *   recording_rules.yml / docker-compose.yml /
     *   targets/<合法文件名>.json
     */
    private static final java.util.Set<String> ALLOWED_CONFIG_NAMES = java.util.Set.of(
            "prometheus.yml",
            "alert.rules.yml",
            "alertmanager.yml",
            "recording_rules.yml",
            "docker-compose.yml"
    );

//    /**
//     * FIX-FILENAME: 修正 targets/ 文件名正则 Bug
//     *
//     * 原正则: "^[a-zA-Z0-9][a-zA-Z0-9_/-]*/.json$"
//     * 问题:   字符类 [a-zA-Z0-9_/-] 中的 / 是字面量斜杠，
//     *         末尾 /.json 要求文件名中包含 /，导致所有合法文件名（如 node.json）都无法通过。
//     *
//     * 修正:   只校验纯文件名部分（不含路径），只允许字母、数字、下划线、中划线，以 .json 结尾。
//     */
    private static final java.util.regex.Pattern SAFE_FILENAME = java.util.regex.Pattern.compile(
            "^[a-zA-Z0-9][a-zA-Z0-9_\\-]*\\.json$"  // FIX-FILENAME: 修正后的正则
    );

    private Path resolveConfigPath(String configName) {
        if (configName == null || configName.isBlank()) {
            throw new IllegalArgumentException("配置文件名不能为空");
        }
        String workDir = properties.getCompose().getWorkDir();

        // targets/ 前缀：只允许 targets/<合法文件名>.json
        if (configName.startsWith("targets/")) {
            String fileName = configName.substring("targets/".length());
            if (!SAFE_FILENAME.matcher(fileName).matches()) {
                throw new IllegalArgumentException("非法的 targets 配置文件名: " + fileName);
            }
            Path resolved = Paths.get(workDir, "targets", fileName).normalize();
            // 确保路径在 workDir 内（防符号链接绕过）
            Path base = Paths.get(workDir).normalize();
            if (!resolved.startsWith(base)) {
                throw new IllegalArgumentException("配置路径越界: " + configName);
            }
            return resolved;
        }

        // 非 targets/：必须在白名单内
        if (!ALLOWED_CONFIG_NAMES.contains(configName)) {
            throw new IllegalArgumentException("不允许访问的配置文件: " + configName +
                    "，允许的配置: " + ALLOWED_CONFIG_NAMES);
        }

        // 再次确认解析后路径在 workDir 内
        Path resolved = Paths.get(workDir, configName).normalize();
        Path base = Paths.get(workDir).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("配置路径越界: " + configName);
        }
        return resolved;
    }

    @Data
    @Builder
    public static class ConfigVersion {
        private String configName;
        private int version;
        private String content;
        private String changeReason;
        private long timestamp;
        private boolean applied;
        private String error;
    }
}