package com.opsmonitor.monitor;

import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.ExporterTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Prometheus 配置统一管理器 (9E-5)
 *
 * 统一管理：
 * - prometheus.yml         → 全局配置 + scrape_configs (File SD per type)
 * - alert.rules.yml        → 告警规则（含类型级规则）
 * - targets/ 目录           → File SD JSON 文件
 *
 * 与其他组件的关系：
 * - PrometheusManagerImpl  → 负责 prometheus.yml 的运行时 static_configs 操作 + reload
 * - PrometheusTargetWriter → 负责 targets/*.json 的写入
 * - ComposeLauncher        → 负责初始 prometheus.yml 模板生成
 * - 本类                   → 负责配置结构的完整性检查 + 缺失配置修复 + 版本升级
 *
 * 设计原则：
 * - 只做增量检查和修复，不破坏已有配置
 * - 在 SystemInitializer 启动时调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrometheusConfigManager {

    private final OpsMonitorProperties properties;
    private final ExporterTemplateRegistry templateRegistry;
    private final PrometheusTargetWriter targetWriter;

    /**
     * 检查并修复 Prometheus 配置完整性
     * 返回修复的项目数
     */
    public int verifyAndRepairConfig() {
        int repaired = 0;

        repaired += ensureTargetsDirectory();
        repaired += ensureAlertRulesFile();
        repaired += ensureAlertmanagerConfig();
        repaired += verifyPrometheusYmlFileSD();

        if (repaired > 0) {
            log.info("PrometheusConfigManager: 修复 {} 项配置", repaired);
        } else {
            log.debug("PrometheusConfigManager: 配置完整，无需修复");
        }

        return repaired;
    }

    /**
     * 确保 targets 目录存在
     */
    private int ensureTargetsDirectory() {
        Path targetsDir = targetWriter.getTargetsDir();
        if (!Files.isDirectory(targetsDir)) {
            targetWriter.ensureTargetsDir();
            log.info("已创建 targets 目录: {}", targetsDir);
            return 1;
        }
        return 0;
    }

    /**
     * 确保 alert.rules.yml 存在
     */
    private int ensureAlertRulesFile() {
        Path rulesFile = Paths.get(properties.getCompose().getWorkDir(), "alert.rules.yml");
        if (!Files.exists(rulesFile)) {
            log.warn("alert.rules.yml 不存在，将由 ComposeLauncher 重新生成");
            return 1;
        }
        return 0;
    }

    /**
     * 确保 alertmanager.yml 存在
     */
    private int ensureAlertmanagerConfig() {
        Path configFile = Paths.get(properties.getCompose().getWorkDir(), "alertmanager.yml");
        if (!Files.exists(configFile)) {
            log.warn("alertmanager.yml 不存在，将由 ComposeLauncher 重新生成");
            return 1;
        }
        return 0;
    }

    /**
     * 检查 prometheus.yml 是否包含 File SD 配置。
     *
     * 修复（v2.3）：
     * 原来只打 WARN、要求手动删除。现在改为：
     * - 检测到旧版（缺少 file_sd_configs）→ 自动删除旧文件 → 由后续 ComposeLauncher 重新生成含 File SD 的版本
     * - 缺少部分类型 File SD → 同样自动重建，保证完整
     */
    private int verifyPrometheusYmlFileSD() {
        Path configPath = Paths.get(properties.getPrometheus().getConfigPath());
        if (!Files.exists(configPath)) {
            // 不存在则 ComposeLauncher 会自动生成，无需处理
            return 0;
        }

        try {
            String content = Files.readString(configPath, StandardCharsets.UTF_8);

            // ① 完全缺失 file_sd_configs → 旧版本，直接删除重建
            if (!content.contains("file_sd_configs")) {
                log.warn("[PrometheusConfigManager] prometheus.yml 为旧版本（缺少 file_sd_configs），自动删除并重新生成");
                Files.delete(configPath);
                log.info("[PrometheusConfigManager] 旧版 prometheus.yml 已删除，将由 ComposeLauncher 重新生成");
                return 1;
            }

            // ② 存在 file_sd_configs 但缺少部分类型 → 同样重建，保证完整
            int missing = 0;
            for (ExporterTemplate tpl : templateRegistry.listAll()) {
                String typeFile = "targets/" + tpl.getType() + ".json";
                if (!content.contains(typeFile)) {
                    missing++;
                }
            }
            if (missing > 0) {
                log.warn("[PrometheusConfigManager] prometheus.yml 缺少 {} 种 Exporter 类型的 File SD，自动重建", missing);
                Files.delete(configPath);
                log.info("[PrometheusConfigManager] prometheus.yml 已删除，将由 ComposeLauncher 重新生成");
                return missing;
            }

            return 0;

        } catch (IOException e) {
            log.error("[PrometheusConfigManager] 读取/删除 prometheus.yml 失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 获取所有已注册的 Exporter 类型
     */
    public List<String> getRegisteredExporterTypes() {
        return templateRegistry.listAll().stream()
                .map(ExporterTemplate::getType)
                .toList();
    }

    /**
     * 获取 prometheus.yml 路径
     */
    public Path getPrometheusConfigPath() {
        return Paths.get(properties.getPrometheus().getConfigPath());
    }

    /**
     * 获取 alert.rules.yml 路径
     */
    public Path getAlertRulesPath() {
        return Paths.get(properties.getCompose().getWorkDir(), "alert.rules.yml");
    }

    /**
     * 获取 alertmanager.yml 路径
     */
    public Path getAlertmanagerConfigPath() {
        return Paths.get(properties.getCompose().getWorkDir(), "alertmanager.yml");
    }

    /**
     * 获取 targets 目录路径
     */
    public Path getTargetsDir() {
        return targetWriter.getTargetsDir();
    }
}