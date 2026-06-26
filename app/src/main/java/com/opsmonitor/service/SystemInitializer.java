package com.opsmonitor.service;

import com.opsmonitor.core.RuntimeLockManager;
import com.opsmonitor.core.SystemIntegrityVerifier;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.docker.ComposeLauncher;
import com.opsmonitor.docker.DockerEnvironmentChecker;
import com.opsmonitor.monitor.DashboardGenerator;
import com.opsmonitor.monitor.ExporterManagerImpl;
import com.opsmonitor.monitor.GrafanaManager;
import com.opsmonitor.monitor.PrometheusConfigManager;
import com.opsmonitor.monitor.PrometheusTargetWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 系统初始化器（9C 企业级版）
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SystemInitializer implements ApplicationRunner {

    private final DockerEnvironmentChecker dockerChecker;
    private final ComposeLauncher composeLauncher;
    private final ExporterManagerImpl exporterManager;
    private final GrafanaManager grafanaManager;
    private final RuntimeLockManager lockManager;
    private final SystemIntegrityVerifier integrityVerifier;
    private final DashboardGenerator dashboardGenerator;
    private final PrometheusTargetWriter targetWriter;
    private final PrometheusConfigManager prometheusConfigManager;
    // v2.10 P1-03:启动时检查 security.enabled,若关闭则 ERROR 告警
    private final OpsMonitorProperties properties;

    private boolean initialized = false;
    private String initError = null;

    @Override
    public void run(ApplicationArguments args) {
        // v2.10 P1-03 修复:启动时检查 security.enabled,若被误改为 false,ERROR 告警
        warnIfSecurityDisabled();

        log.info("============================================");
        log.info("  多服务器一体化运维监控平台 - 初始化开始");
        log.info("============================================");

        try {
            // Step 0: 检查运行态锁
            if (!lockManager.isLockHeld()) {
                log.warn("⚠️ 未获取运行态锁（可能有其他实例运行），继续初始化但功能可能冲突");
            }

            // Step 1: 检查 Docker 环境
            log.info("[Step 1/6] 检查 Docker 环境...");
            if (!dockerChecker.isDockerAvailable()) {
                initError = "Docker 未运行或无法连接，请确保 Docker 已启动";
                log.error("❌ {}", initError);
                log.error("提示: Linux 请执行 'systemctl start docker'");
                log.error("提示: Windows 请启动 Docker Desktop");
                return;
            }
            String dockerVersion = dockerChecker.getDockerVersion();
            log.info("✅ Docker 版本: {}", dockerVersion);

            // Step 2: 检查 docker-compose
            log.info("[Step 2/6] 检查 docker-compose...");
            boolean composeAvailable = dockerChecker.isComposeAvailable();
            if (composeAvailable) {
                log.info("✅ docker-compose 可用");
            } else {
                log.warn("⚠️ docker-compose 不可用，部分功能可能受限");
            }

            // Step 3: 系统完整性检查 + 配置文件确保
            log.info("[Step 3/6] 系统完整性检查...");
            int repaired = integrityVerifier.verifyAndRepair();
            if (repaired > 0) {
                log.info("已自动修复 {} 项配置", repaired);
            }
            log.info("✅ 配置文件就绪");

            // Step 3b: 确保 targets 目录 + 全部配置文件完整性
            // FIX-INIT: 补充 ensureDockerCompose / ensurePrometheusConfig / ensureRecordingRules
            // 原遗漏导致打包运行（java -jar）时 ${user.dir} 改变，
            // docker/ 目录下缺少 docker-compose.yml / prometheus.yml 等文件，
            // Docker 挂载失败报错：OCI runtime create failed: not a directory
            composeLauncher.ensureComposeFile();
            composeLauncher.ensurePrometheusConfig();
            composeLauncher.ensureTargetsDir();
            composeLauncher.ensureAlertRules();
            composeLauncher.ensureRecordingRules();
            composeLauncher.ensureAlertmanagerConfig();
            targetWriter.ensureTargetsDir();
            dashboardGenerator.generateAllDashboards();
            int configRepaired = prometheusConfigManager.verifyAndRepairConfig();
            if (configRepaired > 0) {
                log.info("Prometheus 配置修复 {} 项", configRepaired);
                // FIX-INIT-2: verifyAndRepairConfig 可能会删除旧版 prometheus.yml 并期望重建
                // 必须在删除后立即重新生成，否则 Step 4 composeUp 挂载会失败
                composeLauncher.ensurePrometheusConfig();
            }
            log.info("✅ Targets / AlertRules / Dashboard / Prometheus Config 就绪");

            // Step 4: 启动监控组件
            log.info("[Step 4/6] 启动监控组件 (Prometheus + Grafana)...");
            if (composeAvailable) {
                boolean started = composeLauncher.composeUp();
                if (started) {
                    log.info("✅ 监控组件启动成功");
                } else {
                    log.warn("⚠️ 监控组件启动可能未完全成功，请检查 Docker 日志");
                }
            } else {
                log.warn("⚠️ 跳过自动启动（docker-compose 不可用）");
            }

            // Step 5: 从 Docker 标签恢复托管 Exporter 状态
            log.info("[Step 5/6] 恢复托管 Exporter 状态...");
            try {
                exporterManager.rebuildManagedExportersFromDocker();
                log.info("✅ Exporter 状态恢复完成");
            } catch (Exception e) {
                log.warn("⚠️ Exporter 状态恢复异常（不影响主流程）: {}", e.getMessage());
            }

            // Step 6: Grafana 自动初始化
            log.info("[Step 6/6] Grafana 自动初始化...");
            try {
                grafanaManager.initializeGrafana();
            } catch (Exception e) {
                log.error("⚠️ Grafana 初始化失败（不影响系统运行）: {}", e.getMessage());
            }

            initialized = true;
            log.info("============================================");
            log.info("  OpsMonitor {} — 初始化完成!", properties.getVersion());
            log.info("  经典管理界面: http://127.0.0.1:8080");
            log.info("  Vue 管理后台:  http://127.0.0.1:8080/admin");
            log.info("  Prometheus:    http://127.0.0.1:9090");
            log.info("  Grafana:       http://127.0.0.1:3000");
            log.info("  AlertManager:  http://127.0.0.1:9093");
            log.info("  VictoriaMetrics: http://127.0.0.1:8428");
            log.info("  健康巡检: 已启动 (30s 周期)");
            log.info("  运行态锁: {}", lockManager.isLockHeld() ? "已获取" : "未获取");
            log.info("  CMDB: 就绪");
            log.info("  认证: 就绪 {}", getAuthBannerSuffix());
            log.info("  任务编排: 就绪");
            log.info("  通知渠道: 就绪");
            log.info("============================================");

        } catch (Exception e) {
            initError = "系统初始化异常: " + e.getMessage();
            log.error("❌ {}", initError, e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getInitError() {
        return initError;
    }

    /**
     * v2.10 修复:启动横幅根据环境变量动态显示认证状态
     * 原硬编码"(默认 admin/admin123)"即使用户已设密码也会误导
     */
    private String getAuthBannerSuffix() {
        String adminPwd = System.getenv("OPS_ADMIN_PASSWORD");
        String hmacSecret = System.getenv("OPS_HMAC_SECRET");
        boolean hasAdminPwd = adminPwd != null && !adminPwd.isBlank();
        boolean hasHmacSecret = hmacSecret != null && !hmacSecret.isBlank() && hmacSecret.length() >= 32;
        if (hasAdminPwd && hasHmacSecret) {
            return "(✅ 环境变量已就绪,密码已自定义)";
        }
        if (!hasAdminPwd && !hasHmacSecret) {
            return "⚠️ (默认 admin/admin123 + 弱 HMAC,请立即设置 OPS_ADMIN_PASSWORD + OPS_HMAC_SECRET)";
        }
        if (!hasAdminPwd) {
            return "⚠️ (默认 admin/admin123,请设置 OPS_ADMIN_PASSWORD 并删除 data/users.json)";
        }
        return "⚠️ (HMAC 弱密钥,请设置 OPS_HMAC_SECRET)";
    }

    /**
     * v2.10 P1-03:启动时检查 security.enabled,若被误改为 false 则多次 ERROR 告警
     * 防止配置误操作导致系统完全裸奔
     */
    private void warnIfSecurityDisabled() {
        if (properties.getSecurity() != null && !properties.getSecurity().isEnabled()) {
            log.error("⚠️⚠️⚠️ 严重警告 ⚠️⚠️⚠️");
            log.error("⚠️  security.enabled = false,所有 /api/** 接口将完全跳过认证!");
            log.error("⚠️  这是严重危险配置,生产环境绝不允许。请立即改回 application.yml:");
            log.error("⚠️      ops-monitor.security.enabled: true");
            log.error("⚠️⚠️⚠️ 严重警告 ⚠️⚠️⚠️");
        }
    }
}