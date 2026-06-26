package com.opsmonitor.core;

import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.docker.ComposeLauncher;
import com.opsmonitor.docker.DockerEnvironmentChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 系统完整性验证器 (9H-2 增强版 — StartupVerifier)
 *
 * 在 SystemInitializer 中调用，全面检查：
 * 1. docker-compose.yml 是否为文件（非目录）
 * 2. prometheus.yml 是否为文件（非目录）
 * 3. alert.rules.yml 是否为文件（非目录）
 * 4. alertmanager.yml 是否为文件（非目录）
 * 5. targets 目录是否存在
 * 6. Grafana datasource provisioning 是否存在
 * 7. Grafana dashboard provisioning 是否存在
 *
 * 每一项 Docker Mount Guard：若路径是目录则自动修复
 * 输出: SYSTEM READY / AUTO FIXED n 项
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemIntegrityVerifier {

    private final OpsMonitorProperties properties;
    private final ComposeLauncher composeLauncher;
    private final DockerEnvironmentChecker dockerChecker;

    /**
     * 执行完整性检查
     * @return 修复的文件数量（0=全部正常）
     */
    public int verifyAndRepair() {
        log.info("========== 系统启动自检 (StartupVerifier) ==========");
        int repaired = 0;

        // Hotfix-2: 检查 Docker daemon 是否运行
        if (!checkDockerDaemon()) {
            log.error("========== DOCKER NOT AVAILABLE — 请先启动 Docker Desktop ==========");
            return -1; // 返回 -1 表示致命错误
        }

        String workDir = properties.getCompose().getWorkDir();

        // 1. docker-compose.yml（含 Mount Guard）
        Path composePath = Paths.get(workDir, "docker-compose.yml");
        if (needsRepair(composePath, "docker-compose.yml")) {
            composeLauncher.ensureComposeFile();
            repaired++;
        }

        // 2. prometheus.yml（含 Mount Guard）
        Path promPath = Paths.get(properties.getPrometheus().getConfigPath());
        if (needsRepair(promPath, "prometheus.yml")) {
            composeLauncher.ensurePrometheusConfig();
            repaired++;
        }

        // 3. alert.rules.yml（含 Mount Guard）
        Path alertPath = Paths.get(workDir, "alert.rules.yml");
        if (needsRepair(alertPath, "alert.rules.yml")) {
            composeLauncher.ensureAlertRules();
            repaired++;
        }

        // 3b. recording_rules.yml（v2.5 新增，含 Mount Guard）
        Path recordingPath = Paths.get(workDir, "recording_rules.yml");
        if (needsRepair(recordingPath, "recording_rules.yml")) {
            composeLauncher.ensureRecordingRules();
            repaired++;
        }

        // 4. alertmanager.yml（含 Mount Guard）
        Path amPath = Paths.get(workDir, "alertmanager.yml");
        if (needsRepair(amPath, "alertmanager.yml")) {
            composeLauncher.ensureAlertmanagerConfig();
            repaired++;
        }

        // 5. targets 目录
        Path targetsDir = Paths.get(workDir, "targets");
        if (!Files.isDirectory(targetsDir)) {
            log.warn("[StartupVerifier] 缺失: targets 目录 → 自动创建");
            composeLauncher.ensureTargetsDir();
            repaired++;
        }

        // 6. Grafana datasource provisioning
        Path dsPath = Paths.get(workDir, "grafana", "provisioning", "datasources", "prometheus.yml");
        if (!Files.exists(dsPath) || Files.isDirectory(dsPath)) {
            log.warn("[StartupVerifier] 缺失: Grafana datasource provisioning → 自动重建");
            composeLauncher.ensureGrafanaDatasourceProvisioning();
            repaired++;
        }

        // 7. Grafana dashboard provisioning
        Path dashProviderPath = Paths.get(workDir, "grafana", "provisioning", "dashboards", "dashboard.yml");
        if (!Files.exists(dashProviderPath)) {
            log.warn("[StartupVerifier] 缺失: Grafana dashboard provisioning → 自动重建");
            composeLauncher.ensureGrafanaDashboards();
            repaired++;
        }

        if (repaired == 0) {
            log.info("========== SYSTEM READY — 所有配置文件就绪 ==========");
        } else {
            log.info("========== AUTO FIXED — 已自动修复 {} 项 ==========", repaired);
        }

        return repaired;
    }

    /**
     * 检查路径是否需要修复：不存在 或 是目录（Docker 误创建）
     */
    private boolean needsRepair(Path path, String name) {
        if (Files.isDirectory(path)) {
            log.warn("[StartupVerifier] {} 是目录（Docker 误创建）→ Mount Guard 修复", name);
            return true;
        }
        if (!Files.exists(path)) {
            log.warn("[StartupVerifier] 缺失: {} → 自动重建", name);
            return true;
        }
        // 额外检查文件是否为空
        try {
            if (Files.size(path) == 0) {
                log.warn("[StartupVerifier] {} 为空文件 → 重新生成", name);
                Files.delete(path);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Hotfix-2: 检查 Docker daemon 是否运行
     * 使用 DockerEnvironmentChecker 验证连接 + docker info 验证 daemon
     */
    private boolean checkDockerDaemon() {
        try {
            if (!dockerChecker.isDockerAvailable()) {
                log.error("[StartupVerifier] Docker 不可用");
                return false;
            }
            // 额外执行 docker info 确认 daemon 正常
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("[StartupVerifier] docker info 失败（Docker daemon 未运行）");
                log.error("[StartupVerifier] 请启动 Docker Desktop 后重试");
                return false;
            }
            log.info("[StartupVerifier] Docker daemon 运行正常");
            return true;
        } catch (Exception e) {
            log.error("[StartupVerifier] Docker 检查异常: {}", e.getMessage());
            log.error("[StartupVerifier] 请确认 Docker Desktop 已启动");
            return false;
        }
    }
}