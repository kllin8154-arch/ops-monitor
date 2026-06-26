package com.opsmonitor.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Prometheus 配置校验器 (9H-3)
 *
 * 校验：
 * 1. prometheus.yml — 是文件、非空、包含 scrape_configs
 * 2. targets/*.json — 有效 JSON、targets 非空、labels 包含必须字段
 * 3. alert.rules.yml — 是文件、非空、包含 groups
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrometheusConfigValidator {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行全面校验，返回问题列表
     */
    public List<String> validate() {
        List<String> issues = new ArrayList<>();

        validatePrometheusYml(issues);
        validateTargetsJson(issues);
        validateAlertRules(issues);

        if (issues.isEmpty()) {
            log.info("[ConfigValidator] Prometheus 配置校验通过");
        } else {
            log.warn("[ConfigValidator] 发现 {} 个配置问题", issues.size());
            issues.forEach(i -> log.warn("[ConfigValidator]   - {}", i));
        }

        return issues;
    }

    private void validatePrometheusYml(List<String> issues) {
        Path path = Paths.get(properties.getPrometheus().getConfigPath());
        if (!Files.exists(path)) {
            issues.add("prometheus.yml 不存在");
            return;
        }
        if (Files.isDirectory(path)) {
            issues.add("prometheus.yml 是目录（Docker Mount Guard 未修复）");
            return;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                issues.add("prometheus.yml 为空");
            } else if (!content.contains("scrape_configs")) {
                issues.add("prometheus.yml 缺少 scrape_configs 段");
            }
            if (!content.contains("file_sd_configs")) {
                issues.add("prometheus.yml 缺少 file_sd_configs（旧版本，需重新生成）");
            }
        } catch (IOException e) {
            issues.add("prometheus.yml 读取失败: " + e.getMessage());
        }
    }

    private void validateTargetsJson(List<String> issues) {
        Path targetsDir = Paths.get(properties.getCompose().getWorkDir(), "targets");
        if (!Files.isDirectory(targetsDir)) {
            issues.add("targets 目录不存在");
            return;
        }
        try (var files = Files.list(targetsDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.toString().endsWith(".tmp"))
                    .forEach(jsonFile -> validateSingleTargetJson(jsonFile, issues));
        } catch (IOException e) {
            issues.add("targets 目录读取失败: " + e.getMessage());
        }
    }

    /** Hotfix-3: Dashboard V3 依赖的必须标签 */
    private static final List<String> REQUIRED_LABELS = List.of(
            "exporter_type", "managed_by"
    );

    /** Hotfix-3: 推荐标签（缺少时发出警告，不作为错误） */
    private static final List<String> RECOMMENDED_LABELS = List.of(
            "server_id", "server_name", "exporter_id", "node"
    );

    private void validateSingleTargetJson(Path jsonFile, List<String> issues) {
        String fileName = jsonFile.getFileName().toString();
        try {
            String content = Files.readString(jsonFile, StandardCharsets.UTF_8);
            if (content.isBlank()) return; // 空文件是正常的（已清理的type）

            JsonNode root = mapper.readTree(content);
            if (!root.isArray()) {
                issues.add(fileName + " 不是 JSON 数组");
                return;
            }
            for (JsonNode entry : root) {
                JsonNode targets = entry.path("targets");
                if (!targets.isArray() || targets.isEmpty()) {
                    issues.add(fileName + " 中存在空的 targets");
                }
                JsonNode labels = entry.path("labels");
                // Hotfix-3: 检查必须标签
                for (String required : REQUIRED_LABELS) {
                    if (!labels.has(required) || labels.get(required).asText().isBlank()) {
                        issues.add(fileName + " 中缺少必须标签: " + required);
                    }
                }
                // Hotfix-3: 检查推荐标签（仅警告）
                for (String recommended : RECOMMENDED_LABELS) {
                    if (!labels.has(recommended)) {
                        log.debug("[ConfigValidator] {} 缺少推荐标签: {}", fileName, recommended);
                    }
                }
            }
        } catch (Exception e) {
            issues.add(fileName + " JSON 解析失败: " + e.getMessage());
        }
    }

    private void validateAlertRules(List<String> issues) {
        Path path = Paths.get(properties.getCompose().getWorkDir(), "alert.rules.yml");
        if (!Files.exists(path)) {
            issues.add("alert.rules.yml 不存在");
            return;
        }
        if (Files.isDirectory(path)) {
            issues.add("alert.rules.yml 是目录（Docker Mount Guard 未修复）");
            return;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                issues.add("alert.rules.yml 为空");
            } else if (!content.contains("groups")) {
                issues.add("alert.rules.yml 缺少 groups 段");
            }
        } catch (IOException e) {
            issues.add("alert.rules.yml 读取失败: " + e.getMessage());
        }
    }
}