package com.opsmonitor.platform;

import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.platform.ConfigCenterService;
import com.opsmonitor.monitor.DashboardGenerator;
import com.opsmonitor.monitor.GrafanaManager;
import com.opsmonitor.model.MonitorAgent;
import com.opsmonitor.service.AgentRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 平台初始化器 (10E 增强)
 *
 * 系统启动时自动完成：
 * 1. 配置中心自动纳管现有配置（prometheus.yml / alert.rules.yml / alertmanager.yml）
 * 2. 本机自动注册为 Agent（让 Agent 页面不空）
 * 3. 日志提示
 *
 * 执行顺序：在 SystemInitializer 之后（@Order(200)）
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class PlatformInitializer implements ApplicationRunner {

    private final ConfigCenterService configCenter;
    private final AgentRegistryService agentRegistry;
    private final OpsMonitorProperties properties;
    private final DashboardGenerator   dashboardGenerator;
    private final GrafanaManager       grafanaManager;

    @Override
    public void run(ApplicationArguments args) {
        try {
            initConfigCenter();
            initLocalAgent();
            initDashboards();
            log.info("[PlatformInit] 平台初始化完成");
        } catch (Exception e) {
            log.warn("[PlatformInit] 初始化异常（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 启动时强制生成 V6 仪表盘文件，并触发 Grafana provisioning reload
     *
     * 解决问题：
     * - ResourceReconciler 有60秒延迟且依赖 dirty=true 才执行
     * - 若 Grafana 先于仪表盘生成完成，会看到空列表
     * - 此方法在 Spring 启动完成后立即执行，确保文件就绪
     */
    private void initDashboards() {
        try {
            dashboardGenerator.generateAllDashboards();
            log.info("[PlatformInit] V6 仪表盘文件已生成（基础设施总览/服务健康总览/数据库与中间件）");
            // 触发 Grafana reload，让 Grafana 立即加载新文件
            boolean reloaded = grafanaManager.reloadDashboards();
            if (reloaded) {
                log.info("[PlatformInit] Grafana provisioning reload 成功");
            } else {
                log.warn("[PlatformInit] Grafana provisioning reload 失败（Grafana 可能尚未就绪，将在下次调谐时重试）");
            }
        } catch (Exception e) {
            log.warn("[PlatformInit] 仪表盘初始化失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 配置中心：自动纳管现有配置文件
     */
    private void initConfigCenter() {
        String workDir = properties.getCompose().getWorkDir();

        // 纳管 prometheus.yml
        importConfig(Paths.get(workDir, "prometheus.yml"), "prometheus.yml", "系统启动自动纳管");

        // 纳管 alert.rules.yml
        importConfig(Paths.get(workDir, "alert.rules.yml"), "alert.rules.yml", "系统启动自动纳管");

        // 纳管 alertmanager.yml
        importConfig(Paths.get(workDir, "alertmanager.yml"), "alertmanager.yml", "系统启动自动纳管");

        log.info("[PlatformInit] 配置中心已纳管现有配置");
    }

    private void importConfig(Path filePath, String configName, String reason) {
        try {
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                // 只在首次纳管（版本=0时）
                if (configCenter.getCurrentVersion(configName) == 0) {
                    String content = Files.readString(filePath, StandardCharsets.UTF_8);
                    configCenter.commitConfig(configName, content, reason);
                }
            }
        } catch (Exception e) {
            log.debug("[PlatformInit] 纳管 {} 失败: {}", configName, e.getMessage());
        }
    }

    /**
     * 本机自动注册为 Agent（让 Agent 管理页面有数据）
     */
    private void initLocalAgent() {
        String agentId = "local-agent";

        // 已存在则跳过
        if (agentRegistry.getAgent(agentId) != null) return;

        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
            int cpuCores = Runtime.getRuntime().availableProcessors();
            long memoryMb = Runtime.getRuntime().totalMemory() / (1024 * 1024);
            // 尝试获取系统总内存
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                memoryMb = sunOs.getTotalMemorySize() / (1024 * 1024);
            }

            MonitorAgent agent = MonitorAgent.builder()
                    .agentId(agentId)
                    .hostname(hostname)
                    .ip("127.0.0.1")
                    .os(os)
                    .cpuCores(cpuCores)
                    .memoryMb(memoryMb)
                    .agentVersion("2.0.0")
                    .exporterTypes(List.of("node"))
                    .build();

            agentRegistry.register(agent);
            log.info("[PlatformInit] 本机已注册为 Agent: {} ({}核/{}MB)", hostname, cpuCores, memoryMb);
        } catch (Exception e) {
            log.debug("[PlatformInit] 注册本机 Agent 失败: {}", e.getMessage());
        }
    }
}