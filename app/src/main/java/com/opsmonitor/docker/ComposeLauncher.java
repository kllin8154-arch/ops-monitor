package com.opsmonitor.docker;

import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Docker Compose 启动器
 * 负责生成 docker-compose.yml 并拉起监控组件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComposeLauncher {

    private final OpsMonitorProperties properties;

    /** v2.27: 全局 compose 操作锁，防止多线程并发执行 up/down */
    private final AtomicBoolean composeLock = new AtomicBoolean(false);

    /**
     * 确保 docker-compose.yml 存在，不存在则生成
     * 9H-1: Docker Mount Guard
     */
    public void ensureComposeFile() {
        Path composeDir = Paths.get(properties.getCompose().getWorkDir());
        Path composeFile = composeDir.resolve("docker-compose.yml");

        try {
            Files.createDirectories(composeDir);
            validateMountFile(composeFile);

            if (Files.exists(composeFile)) {
                // 检查是否包含最新 provisioning 挂载
                String existing = Files.readString(composeFile, StandardCharsets.UTF_8);
                if (!existing.contains("grafana/provisioning:/etc/grafana/provisioning")) {
                    log.info("docker-compose.yml 版本过旧（缺少 provisioning 挂载），重新生成");
                    String content = generateComposeContent();
                    Files.writeString(composeFile, content, StandardCharsets.UTF_8);
                    log.info("docker-compose.yml 已更新");
                } else {
                    log.info("docker-compose.yml 已存在且版本正确: {}", composeFile);
                }
            } else {
                log.info("首次启动，生成 docker-compose.yml: {}", composeFile);
                String content = generateComposeContent();
                Files.writeString(composeFile, content, StandardCharsets.UTF_8);
                log.info("docker-compose.yml 生成成功");
            }
        } catch (IOException e) {
            log.error("生成 docker-compose.yml 失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法生成 docker-compose.yml", e);
        }
    }

    /**
     * 确保 prometheus.yml 存在
     * 9H-1: Docker Mount Guard — 检测并修复 Docker 误创建的同名目录
     */
    public void ensurePrometheusConfig() {
        Path configPath = Paths.get(properties.getPrometheus().getConfigPath());

        try {
            Files.createDirectories(configPath.getParent());
            validateMountFile(configPath);

            if (!Files.exists(configPath)) {
                log.info("生成初始 prometheus.yml: {}", configPath);
                String content = generatePrometheusConfig();
                Files.writeString(configPath, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("生成 prometheus.yml 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 确保 Grafana Provisioning 数据源配置存在
     * 通过文件 provisioning 方式自动创建 Prometheus 数据源
     * 这是 Grafana 官方推荐方式，不依赖 HTTP API 认证
     */
    public void ensureGrafanaDatasourceProvisioning() {
        String workDir = properties.getCompose().getWorkDir();
        Path dsDir = Paths.get(workDir, "grafana", "provisioning", "datasources");
        Path dsFile = dsDir.resolve("prometheus.yml");

        try {
            Files.createDirectories(dsDir);

            // V6修复：每次启动都强制重写数据源配置（保证 uid/deleteDatasources 正确生效）
            // 旧的 provisioning 文件可能缺少 deleteDatasources 或 uid 配置导致 Grafana 崩溃
            log.info("生成/更新 Grafana 数据源 provisioning: {}", dsFile);
            String dsContent = generateGrafanaDatasourceProvisioning();
            Files.writeString(dsFile, dsContent, StandardCharsets.UTF_8);
            log.info("Grafana 数据源 provisioning 已写入（含 VictoriaMetrics uid + deleteDatasources）");
        } catch (IOException e) {
            log.error("生成 Grafana 数据源 provisioning 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 确保 Grafana Dashboard Provisioning 存在（v2.3 修复：清理旧版残留 Dashboard）
     *
     * 问题背景：
     * DashboardGenerator V5 生成 global-overview.json（uid=ops-global-overview）
     * 但旧版曾生成过 system-overview.json（uid 不同），导致 Grafana 同时加载两个
     * "System Overview" Dashboard，出现重复条目。
     *
     * 修复策略：
     * - 启动时扫描 dashboards 目录，删除已知的旧版文件名
     * - 只清理文件名，不影响当前 V5 生成的 5 个 Dashboard JSON
     */
    public void ensureGrafanaDashboards() {
        String workDir = properties.getCompose().getWorkDir();
        Path dashDir = Paths.get(workDir, "grafana", "provisioning", "dashboards");

        try {
            Files.createDirectories(dashDir);

            // Dashboard provider 配置
            Path providerFile = dashDir.resolve("dashboard.yml");
            if (!Files.exists(providerFile)) {
                log.info("生成 Grafana Dashboard provider: {}", providerFile);
                Files.writeString(providerFile, generateDashboardProvider(), StandardCharsets.UTF_8);
            }

            // v2.3 修复：清理旧版残留 Dashboard 文件（避免 Grafana 重复加载）
            cleanLegacyDashboards(dashDir);

            log.info("Grafana Dashboard provisioning 就绪");
        } catch (IOException e) {
            log.error("生成 Grafana Dashboard provisioning 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 清理旧版残留 Dashboard JSON 文件
     *
     * 旧版文件名列表（与当前 V5 五个文件不同，均可安全删除）：
     *   system-overview.json  → 被 global-overview.json 取代
     *   overview.json         → 更早版本
     *   host-dashboard.json   → 更早版本
     *   node-dashboard.json   → 更早版本
     *
     * 当前 V5 保留文件（不删除）：
     *   global-overview.json / agent-dashboard.json / project-overview.json
     *   service-overview.json / exporter-health.json / dashboard.yml
     */
    private void cleanLegacyDashboards(Path dashDir) {
        // V6 当前保留的活跃文件（绝不能删除）
        java.util.Set<String> keepV6 = java.util.Set.of(
                "infra-overview.json", "service-health.json", "middleware.json", "dashboard.yml"
        );
        // 已知旧版文件名（可持续追加）
        java.util.List<String> legacyFiles = java.util.List.of(
                "system-overview.json", "overview.json",
                "host-dashboard.json",  "node-dashboard.json",
                // V5 废弃的仪表盘（V6 已用新文件名替代）
                "global-overview.json", "windows-overview.json",
                "agent-dashboard.json", "project-overview.json",
                "service-overview.json", "exporter-health.json"
        );
        for (String fileName : legacyFiles) {
            if (keepV6.contains(fileName)) continue; // 双重保护
            Path legacy = dashDir.resolve(fileName);
            if (Files.exists(legacy)) {
                try {
                    Files.delete(legacy);
                    log.info("[DashboardCleanup] 已删除旧版 Dashboard 文件: {}", fileName);
                } catch (IOException e) {
                    log.warn("[DashboardCleanup] 删除旧版 Dashboard 失败 {}: {}", fileName, e.getMessage());
                }
            }
        }
    }

    /**
     * 确保 alert rules 文件存在
     * 9H-1: Docker Mount Guard
     */
    public void ensureAlertRules() {
        Path rulesFile = Paths.get(properties.getCompose().getWorkDir(), "alert.rules.yml");
        try { validateMountFile(rulesFile); } catch (IOException e) { log.error("修复 alert.rules.yml 失败: {}", e.getMessage()); }
        if (!Files.exists(rulesFile)) {
            try {
                Files.writeString(rulesFile, generateAlertRules(), StandardCharsets.UTF_8);
                log.info("告警规则文件已生成: {}", rulesFile);
            } catch (IOException e) {
                log.error("生成 alert.rules.yml 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * v2.5 Recording Rules：确保 recording_rules.yml 存在
     * Docker Mount Guard：若路径是目录则先删除再生成文件
     */
    public void ensureRecordingRules() {
        Path rulesFile = Paths.get(properties.getCompose().getWorkDir(), "recording_rules.yml");
        try { validateMountFile(rulesFile); } catch (IOException e) { log.error("修复 recording_rules.yml 失败: {}", e.getMessage()); }
        if (!Files.exists(rulesFile)) {
            try {
                Files.writeString(rulesFile, generateRecordingRules(), StandardCharsets.UTF_8);
                log.info("recording_rules.yml 已生成: {}", rulesFile);
            } catch (IOException e) {
                log.error("生成 recording_rules.yml 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 确保 targets 目录存在
     */
    public void ensureTargetsDir() {
        Path targetsDir = Paths.get(properties.getCompose().getWorkDir(), "targets");
        try {
            Files.createDirectories(targetsDir);
        } catch (IOException e) {
            log.error("创建 targets 目录失败: {}", e.getMessage());
        }
    }

    /**
     * 确保 alertmanager 配置文件存在
     * 9H-1: Docker Mount Guard
     */
    public void ensureAlertmanagerConfig() {
        Path configFile = Paths.get(properties.getCompose().getWorkDir(), "alertmanager.yml");
        try { validateMountFile(configFile); } catch (IOException e) { log.error("修复 alertmanager.yml 失败: {}", e.getMessage()); }
        if (!Files.exists(configFile)) {
            try {
                Files.writeString(configFile, generateAlertmanagerConfig(), StandardCharsets.UTF_8);
                log.info("alertmanager.yml 已生成: {}", configFile);
            } catch (IOException e) {
                log.error("生成 alertmanager.yml 失败: {}", e.getMessage());
            }
        }
    }

    private String generateAlertmanagerConfig() {
        return """
                global:
                  resolve_timeout: 5m
                
                route:
                  receiver: 'ops-webhook'
                  group_by: ['alertname', 'server_name']
                  group_wait: 30s
                  group_interval: 5m
                  repeat_interval: 4h
                
                receivers:
                  - name: 'ops-webhook'
                    webhook_configs:
                      - url: 'http://host.docker.internal:8080/api/alerts/webhook'
                        send_resolved: true
                """;
    }

    /**
     /**
     * 启动 docker-compose
     *
     * v2.10 修复历程:
     * - P1-01: Linux 分支改参数数组防命令注入
     * - P1-05 副作用修复:首次启动可能拉大量镜像(~830MB),readProcessOutput 同步阻塞会卡死
     *   → 改为后台线程读流 + waitFor 带超时(最大 10 分钟)
     *   → 每 30 秒打一次"仍在拉镜像"进度提示,避免用户以为 Spring Boot 挂了
     */
    public boolean composeUp() {
        // v2.27: 防并发——同一时间只允许一个 composeUp/Down 执行
        if (!composeLock.compareAndSet(false, true)) {
            log.info("[ComposeLauncher] 已有 compose 操作正在执行，跳过本次调用");
            return true;
        }

        try {
            String workDir = properties.getCompose().getWorkDir();
            log.info("启动 docker-compose,工作目录: {}", workDir);
            log.info("💡 如使用固定版本镜像且首次启动,可能需要 3-10 分钟拉取镜像(约 830MB)");
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // P0-4 fix: 不再经过 cmd /c（与 Linux 分支统一，消除 shell 注入风险）
                // Windows 下 docker-compose.exe 可直接调用，不需要 cmd /c 中转
                pb = buildComposeCommand(false,
                        "-f", "docker-compose.yml",
                        "-p", properties.getCompose().getProjectName(),
                        "up", "-d");
            } else {
                // v2.10 P1-01 修复:不再用 sh -c 字符串拼接(projectName 进 shell 有注入风险)
                pb = buildComposeCommand(false,
                        "-f", "docker-compose.yml",
                        "-p", properties.getCompose().getProjectName(),
                        "up", "-d");
            }

            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // v2.10 修复:后台线程异步读输出,避免 pipe buffer 满 + 同步阻塞挂死
            // 同时 waitFor 带超时(最大 10 分钟,足够任何正常的镜像拉取)
            final StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (output) { output.append(line).append('\n'); }
                        // 实时透传关键进度
                        if (line.contains("Pulling") || line.contains("Pulled")
                                || line.contains("Creating") || line.contains("Started")
                                || line.contains("Downloaded") || line.contains("Error")) {
                            log.info("[docker-compose] {}", line);
                        }
                    }
                } catch (java.io.IOException ignored) {}
            }, "compose-up-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // 心跳日志线程:每 30s 报告一次仍在等待
            Thread heartbeat = new Thread(() -> {
                int elapsed = 0;
                try {
                    while (process.isAlive()) {
                        Thread.sleep(30_000);
                        elapsed += 30;
                        log.info("[ComposeLauncher] docker-compose 仍在运行中...已等待 {}s(首次拉镜像可能需要 3-10 分钟)", elapsed);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "compose-up-heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            // 总超时 10 分钟
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                log.error("docker-compose 启动超过 10 分钟仍未完成,强制终止。可能是网络问题或镜像源不可达。");
                process.destroyForcibly();
                return false;
            }
            heartbeat.interrupt();
            // 等读线程完整消费完剩余输出(进程已退出,应该立即结束)
            readerThread.join(3_000);

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("docker-compose 启动成功");
                log.debug("输出: {}", output.toString());
                return true;
            } else {
                log.error("docker-compose 启动失败,退出码: {}, 输出:\n{}", exitCode, output.toString());
                return false;
            }
        } catch (Exception e) {
            log.error("docker-compose 执行异常: {}", e.getMessage(), e);
            return false;
        } finally {
            composeLock.set(false);
        }
    }

    /**
     * 停止 docker-compose
     */
    public boolean composeDown() {
        // v2.27: 防并发
        if (!composeLock.compareAndSet(false, true)) {
            log.info("[ComposeLauncher] 已有 compose 操作正在执行，跳过 compose down");
            return false;
        }

        try {
            String workDir = properties.getCompose().getWorkDir();
            log.info("停止 docker-compose");
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // P0-4 fix: 与 composeUp 统一，去掉 cmd /c
                pb = buildComposeCommand(false,
                        "-p", properties.getCompose().getProjectName(),
                        "down");
            } else {
                // v2.10 P1-01 修复:改参数数组,不再 sh -c 拼接
                pb = buildComposeCommand(false,
                        "-p", properties.getCompose().getProjectName(),
                        "down");
            }

            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            // v2.10 P2-03 修复:必须读输出流,避免子进程输出 >64KB pipe buffer 时阻塞写入
            // 原代码 `process.waitFor()` 直接等,有挂死风险
            String output = readProcessOutput(process);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("docker-compose 停止退出码 {},输出: {}", exitCode, output);
            }
            return exitCode == 0;
        } catch (Exception e) {
            log.error("docker-compose 停止异常: {}", e.getMessage(), e);
            return false;
        } finally {
            composeLock.set(false);
        }
    }

    /**
     * v2.10 P1-01 辅助:构造 docker compose 命令的 ProcessBuilder(Linux/Mac)
     *
     * 不使用 sh -c 和字符串拼接,避免命令注入。
     * 根据 `isV2AvailableCache` 决定用 `docker compose` 还是 `docker-compose`。
     * 首次调用时通过 `which docker-compose` 探测 V2,结果缓存到静态变量。
     *
     * @param useStderrOut 是否把 stderr 并入 stdout(大部分场景应为 true,此处由调用者控制)
     * @param args         docker compose 子命令及参数,每个元素为独立参数
     * @return 已填充 command 数组的 ProcessBuilder
     */
    private ProcessBuilder buildComposeCommand(boolean useStderrOut, String... args) {
        boolean useV2 = isDockerComposeV2Available();
        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (useV2) {
            cmd.add("docker");
            cmd.add("compose");
        } else {
            cmd.add("docker-compose");
        }
        for (String a : args) {
            cmd.add(a);
        }
        return new ProcessBuilder(cmd);
    }

    /** v2.10 P1-01:缓存 docker compose V2 可用性,只探测一次 */
    private static volatile Boolean V2_AVAILABLE_CACHE = null;

    private boolean isDockerComposeV2Available() {
        Boolean cached = V2_AVAILABLE_CACHE;
        if (cached != null) return cached;
        synchronized (ComposeLauncher.class) {
            if (V2_AVAILABLE_CACHE != null) return V2_AVAILABLE_CACHE;
            try {
                // 用参数数组,不走 shell
                Process p = new ProcessBuilder("docker", "compose", "version")
                        .redirectErrorStream(true)
                        .start();
                // 读输出防止 pipe 阻塞
                try (java.io.InputStream is = p.getInputStream()) {
                    is.readAllBytes();
                }
                V2_AVAILABLE_CACHE = (p.waitFor() == 0);
            } catch (Exception e) {
                V2_AVAILABLE_CACHE = false;
            }
            log.info("[ComposeLauncher] Docker Compose V2 {}", V2_AVAILABLE_CACHE ? "可用,使用 'docker compose'" : "不可用,回退 'docker-compose'");
            return V2_AVAILABLE_CACHE;
        }
    }

    /**
     * 生成 docker-compose.yml 内容
     */
    private String generateComposeContent() {
        int promPort = properties.getPrometheus().getPort();
        int grafanaPort = properties.getGrafana().getPort();
        String adminUser = properties.getGrafana().getAdminUser();
        String adminPass = properties.getGrafana().getAdminPassword();
        int retentionDays = properties.getPrometheus().getRetentionDays();

        return """
                services:
                  prometheus:
                    image: prom/prometheus:latest
                    container_name: ops-prometheus
                    restart: unless-stopped
                    ports:
                      - "%d:9090"
                    volumes:
                      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
                      - ./targets:/etc/prometheus/targets:ro
                      - ./alert.rules.yml:/etc/prometheus/alert.rules.yml:ro
                      - ./recording_rules.yml:/etc/prometheus/recording_rules.yml:ro
                      - prometheus_data:/prometheus
                    command:
                      - '--config.file=/etc/prometheus/prometheus.yml'
                      - '--storage.tsdb.path=/prometheus'
                      - '--storage.tsdb.retention.time=%dd'
                      - '--web.enable-lifecycle'
                      - '--web.console.libraries=/usr/share/prometheus/console_libraries'
                      - '--web.console.templates=/usr/share/prometheus/consoles'
                    networks:
                      - ops-network
                
                  alertmanager:
                    image: prom/alertmanager:latest
                    container_name: ops-alertmanager
                    restart: unless-stopped
                    ports:
                      - "9093:9093"
                    volumes:
                      - ./alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
                    command:
                      - '--config.file=/etc/alertmanager/alertmanager.yml'
                    networks:
                      - ops-network
                
                  grafana:
                    image: grafana/grafana:latest
                    container_name: ops-grafana
                    restart: unless-stopped
                    ports:
                      - "%d:3000"
                    environment:
                      - GF_SECURITY_ADMIN_USER=%s
                      - GF_SECURITY_ADMIN_PASSWORD=%s
                      - GF_SECURITY_ALLOW_EMBEDDING=true
                      - GF_AUTH_ANONYMOUS_ENABLED=false
                      - GF_AUTH_BASIC_ENABLED=true
                      - GF_SECURITY_DISABLE_GRAVATAR=true
                      - GF_SNAPSHOTS_EXTERNAL_ENABLED=false
                    volumes:
                      - grafana_data:/var/lib/grafana
                      - ./grafana/provisioning:/etc/grafana/provisioning:ro
                    depends_on:
                      - prometheus
                    networks:
                      - ops-network
                
                  node-exporter:
                    image: prom/node-exporter:latest
                    container_name: ops-node-exporter
                    restart: unless-stopped
                    ports:
                      - "9100:9100"
                    volumes:
                      - /proc:/host/proc:ro
                      - /sys:/host/sys:ro
                      - /:/rootfs:ro
                    command:
                      - '--path.procfs=/host/proc'
                      - '--path.sysfs=/host/sys'
                      - '--path.rootfs=/rootfs'
                      - '--collector.filesystem.mount-points-exclude=^/(sys|proc|dev|host|etc)($$|/)'
                    networks:
                      - ops-network
                
                  victoria-metrics:
                    image: victoriametrics/victoria-metrics:latest
                    container_name: ops-victoria
                    restart: unless-stopped
                    ports:
                      - "8428:8428"
                    volumes:
                      - victoria_data:/victoria-metrics-data
                    command:
                      - '-retentionPeriod=365d'
                      - '-httpListenAddr=:8428'
                    networks:
                      - ops-network
                
                volumes:
                  prometheus_data:
                  grafana_data:
                  victoria_data:
                
                networks:
                  ops-network:
                    driver: bridge
                """.formatted(promPort, retentionDays, grafanaPort, adminUser, adminPass);
    }

    /**
     * 生成初始 prometheus.yml（v2.3 修复：补全 file_sd_configs 段，避免每次重启都提示旧版本）
     *
     * File SD 设计：
     * - 每种 Exporter 类型对应一个 targets/<type>.json
     * - 由 PrometheusTargetWriter 负责写入，Prometheus 热加载发现
     * - 此处为常见类型预置 File SD job，保证生成即可用
     */
    private String generatePrometheusConfig() {
        return """
                global:
                  scrape_interval: 15s
                  evaluation_interval: 15s

                rule_files:
                  - /etc/prometheus/alert.rules.yml

                alerting:
                  alertmanagers:
                    - static_configs:
                        - targets: ['ops-alertmanager:9093']

                scrape_configs:
                  - job_name: 'prometheus'
                    static_configs:
                      - targets: ['localhost:9090']

                  - job_name: 'node-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/node.json']
                        refresh_interval: 30s
                    static_configs:
                      - targets: ['ops-node-exporter:9100']
                        labels:
                          server_name: '本机'
                          server_id: 'local'
                          managed_by: 'ops-monitor'
                          exporter_type: 'node'

                  - job_name: 'redis-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/redis.json']
                        refresh_interval: 30s

                  - job_name: 'mysql-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/mysql.json']
                        refresh_interval: 30s

                  - job_name: 'postgres-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/postgres.json']
                        refresh_interval: 30s

                  - job_name: 'nginx-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/nginx.json']
                        refresh_interval: 30s

                  - job_name: 'jmx-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/jmx.json']
                        refresh_interval: 30s

                  - job_name: 'process-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/process.json']
                        refresh_interval: 30s

                  - job_name: 'oracle-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/oracle.json']
                        refresh_interval: 30s

                  - job_name: 'kingbase-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/kingbase.json']
                        refresh_interval: 30s

                  - job_name: 'dm-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/dm.json']
                        refresh_interval: 30s

                  - job_name: 'geoserver-exporter'
                    file_sd_configs:
                      - files: ['/etc/prometheus/targets/geoserver.json']
                        refresh_interval: 30s

                remote_write:
                  - url: http://ops-victoria:8428/api/v1/write
                """;
    }

    /**
     * 生成 Grafana Datasource Provisioning YAML
     * Grafana 启动时自动读取此文件创建 Prometheus 数据源
     */
    private String generateGrafanaDatasourceProvisioning() {
        return """
apiVersion: 1

deleteDatasources:
  - name: Prometheus
    orgId: 1

datasources:
  - name: VictoriaMetrics
    type: prometheus
    access: proxy
    uid: VictoriaMetrics
    url: http://ops-victoria:8428
    isDefault: true
    editable: true
    version: 1
    jsonData:
      timeInterval: "15s"
      httpMethod: POST

  - name: Prometheus
    type: prometheus
    access: proxy
    uid: prometheus-local
    url: http://ops-prometheus:9090
    isDefault: false
    editable: true
    version: 1
    jsonData:
      timeInterval: "15s"
      httpMethod: POST
""";
    }

    /**
     * 生成 Dashboard Provider YAML
     */
    private String generateDashboardProvider() {
        return """
                apiVersion: 1
                
                providers:
                  - name: 'OpsMonitor'
                    orgId: 1
                    folder: ''
                    type: file
                    disableDeletion: false
                    editable: true
                    updateIntervalSeconds: 30
                    options:
                      path: /etc/grafana/provisioning/dashboards
                      foldersFromFilesStructure: false
                """;
    }
    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        if (Files.isDirectory(dir)) {
            try (var entries = Files.list(dir)) {
                var list = entries.toList();
                for (Path entry : list) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        Files.delete(dir);
    }

    private String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * 生成 Prometheus 告警规则
     */
    /**
     * v2.5 Recording Rules 内容（SLI/SLO 预计算）
     */
    private String generateRecordingRules() {
        return """
                groups:
                  - name: sli_recording
                    interval: 30s
                    rules:
                      - record: instance:node_cpu_utilization:rate5m
                        expr: >
                          1 - avg without(cpu, mode)(
                            rate(node_cpu_seconds_total{mode="idle"}[5m])
                          )
                      - record: instance:node_memory_utilization:ratio
                        expr: >
                          1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes
                      - record: instance:node_disk_utilization:ratio
                        expr: >
                          1 - (
                            node_filesystem_avail_bytes{mountpoint="/",fstype!="tmpfs"}
                            / node_filesystem_size_bytes{mountpoint="/",fstype!="tmpfs"}
                          )
                      - record: instance:windows_cpu_utilization:rate5m
                        expr: >
                          1 - sum by(instance,server_name)(
                            rate(windows_cpu_time_total{mode="idle"}[5m])
                          ) / sum by(instance,server_name)(
                            rate(windows_cpu_time_total[5m])
                          )
                  - name: exporter_sli
                    interval: 30s
                    rules:
                      - record: ops:exporter_availability:ratio
                        expr: avg(up{managed_by="ops-monitor"})
                      - record: ops:exporter_availability_by_server:ratio
                        expr: avg by(server_id,server_name)(up{managed_by="ops-monitor"})
                """;
    }

    private String generateAlertRules() {
        return """
                groups:
                  - name: ops-monitor-alerts
                    rules:
                      - alert: HighCPU
                        expr: 100 - (avg by(instance)(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100) > 80
                        for: 2m
                        labels:
                          severity: warning
                        annotations:
                          summary: "CPU 使用率过高 ({{ $labels.instance }})"
                          description: "CPU 使用率超过 80%，当前值: {{ $value }}%"
                
                      - alert: HighMemory
                        expr: (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100 > 85
                        for: 2m
                        labels:
                          severity: warning
                        annotations:
                          summary: "内存使用率过高 ({{ $labels.instance }})"
                          description: "内存使用率超过 85%，当前值: {{ $value }}%"
                
                      - alert: HighDisk
                        expr: (1 - node_filesystem_avail_bytes{fstype!~"tmpfs|overlay"} / node_filesystem_size_bytes{fstype!~"tmpfs|overlay"}) * 100 > 90
                        for: 5m
                        labels:
                          severity: critical
                        annotations:
                          summary: "磁盘使用率过高 ({{ $labels.instance }})"
                          description: "磁盘使用率超过 90%，当前值: {{ $value }}%"
                
                      - alert: ExporterDown
                        expr: up{managed_by="ops-monitor"} == 0
                        for: 1m
                        labels:
                          severity: critical
                        annotations:
                          summary: "Exporter 离线 ({{ $labels.job }})"
                          description: "{{ $labels.server_name }} 的 {{ $labels.exporter_type }} Exporter 已离线"
                """;
    }

    /**
     * Mount Guard (9H-1)
     * 检测文件是否被 Docker 挂载为空目录（Docker bind mount 的已知问题）。
     * 若文件存在但大小为 0 且可能是目录挂载覆盖，则重新生成。
     */
    private void validateMountFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) return;
        // 如果文件是目录（Docker 把文件挂载点创建成了目录），删除并准备重建
        if (Files.isDirectory(filePath)) {
            log.warn("[MountGuard] {} 被 Docker 挂载为目录，清理并重建", filePath.getFileName());
            deleteDirectoryRecursively(filePath);
            return;
        }
        // 如果文件大小为 0，可能是空挂载，记录警告
        if (Files.size(filePath) == 0) {
            log.warn("[MountGuard] {} 大小为 0，可能被 Docker 空挂载覆盖，将重新生成",
                    filePath.getFileName());
            Files.delete(filePath);
        }
    }
}