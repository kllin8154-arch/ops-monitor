package com.opsmonitor.monitor;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.model.ExporterRegisterRequest;
import com.opsmonitor.model.ExporterTemplate;
import com.opsmonitor.model.ServerNode;
import com.opsmonitor.service.ServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import com.opsmonitor.service.GrafanaSyncService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exporter 管理器实现
 *
 * 9G-1 强化：
 * - ReentrantLock 保护 register/unregister/load
 * - PrometheusReloadService debounce reload
 */
@Slf4j
@Service
public class ExporterManagerImpl implements ExporterManager {

    private final DockerClient dockerClient;
    private final PrometheusManager prometheusManager;
    private final PrometheusTargetWriter targetWriter;
    private final ExporterTemplateRegistry templateRegistry;
    private final OpsMonitorProperties properties;
    private final ObjectMapper jsonMapper;
    private final ServerService serverService;
    private final PrometheusReloadService reloadService;

    /** 仪表盘同步服务（@Lazy 避免循环依赖） */
    @Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GrafanaSyncService grafanaSyncService;

    /** FIX-THREAD: 异步任务线程池（Grafana同步 + VM清理），替代裸 new Thread() */
    private static final java.util.concurrent.ExecutorService OPS_ASYNC_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "ops-async");
                t.setDaemon(true);
                return t;
            });

    public static final String LABEL_MANAGED = "ops.monitor.managed";
    public static final String LABEL_TYPE = "ops.monitor.type";
    public static final String LABEL_JOB_NAME = "ops.monitor.job_name";
    public static final String LABEL_METRICS_PORT = "ops.monitor.metrics_port";
    public static final String LABEL_EXPORTER_ID = "ops.monitor.exporter_id";

    private final Map<String, ExporterInstance> exporterInstances = new ConcurrentHashMap<>();
    /** 9G-1: 并发保护锁 */
    private final ReentrantLock exporterLock = new ReentrantLock();

    public ExporterManagerImpl(DockerClient dockerClient,
                               PrometheusManager prometheusManager,
                               PrometheusTargetWriter targetWriter,
                               ExporterTemplateRegistry templateRegistry,
                               OpsMonitorProperties properties,
                               @Lazy ServerService serverService,
                               PrometheusReloadService reloadService) {
        this.dockerClient = dockerClient;
        this.prometheusManager = prometheusManager;
        this.targetWriter = targetWriter;
        this.templateRegistry = templateRegistry;
        this.properties = properties;
        this.serverService = serverService;
        this.reloadService = reloadService;
        this.jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ==================== 持久化 ====================

    private Path getExportersFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "exporters.json").normalize();
    }

    /** 保存所有 Exporter 到 JSON（原子写入）*/
    private void saveExporters() {
        try {
            Path path = getExportersFile();
            Path tmp  = path.resolveSibling("exporters.json.tmp");
            Files.createDirectories(path.getParent());
            jsonMapper.writeValue(tmp.toFile(), new ArrayList<>(exporterInstances.values()));
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存 exporters.json 失败: {}", e.getMessage());
        }
    }

    /** 从 JSON 恢复所有 Exporter（含远程），并确保 Prometheus target 存在 */
    public void loadExportersFromJson() {
        exporterLock.lock();
        try {
            loadExportersFromJsonInternal();
        } finally {
            exporterLock.unlock();
        }
    }

    private void loadExportersFromJsonInternal() {
        Path path = getExportersFile();
        if (!Files.exists(path)) return;
        try {
            List<ExporterInstance> list = jsonMapper.readValue(path.toFile(),
                    jsonMapper.getTypeFactory().constructCollectionType(List.class, ExporterInstance.class));
            List<String> existingJobs = prometheusManager.listScrapeJobs();
            int restored = 0;
            int targetsAdded = 0;
            for (ExporterInstance inst : list) {
                if (!exporterInstances.containsKey(inst.getId())) {
                    // 修复：旧版本 exporters.json 没有 serverName 字段，恢复时补填
                    if (inst.getServerName() == null || inst.getServerName().isBlank()) {
                        try {
                            ServerNode node = serverService.getServer(
                                    inst.getServerId() != null ? inst.getServerId() : "local");
                            inst.setServerName(node.getName());
                        } catch (Exception ignored) {
                            inst.setServerName(inst.getServerId() != null ? inst.getServerId() : "unknown");
                        }
                    }
                    exporterInstances.put(inst.getId(), inst);
                    restored++;

                    // P0-3: 确保 Prometheus 中有对应 scrape job
                    if (!existingJobs.contains(inst.getJobName()) && inst.getScrapeTarget() != null) {
                        Map<String, String> labels = new LinkedHashMap<>();
                        labels.put("exporter_type", inst.getType());
                        labels.put("managed_by", "ops-monitor");
                        labels.put("server_id", inst.getServerId() != null ? inst.getServerId() : "local");
                        // BUG-9修复：获取 server name 时优先用 inst.getServerName()，
                        // 避免 ServerNode 查询失败时回退为 server_id 字符串
                        String serverName = inst.getServerName(); // 先用已存储的名称
                        if (serverName == null || serverName.isBlank()) {
                            serverName = labels.get("server_id"); // 次选 server_id
                        }
                        try {
                            ServerNode node = serverService.getServer(labels.get("server_id"));
                            if (node.getName() != null && !node.getName().isBlank()) {
                                serverName = node.getName(); // 最优：实时从 ServerNode 取
                            }
                        } catch (Exception ignored) {}
                        labels.put("server_name", serverName);
                        prometheusManager.addScrapeTarget(inst.getJobName(),
                                List.of(inst.getScrapeTarget()), labels);
                        targetsAdded++;
                    }
                }
            }
            if (targetsAdded > 0) {
                try { prometheusManager.reloadConfig(); } catch (Exception e) {
                    log.warn("恢复后 Prometheus reload 失败: {}", e.getMessage());
                }
            }
            log.info("从 exporters.json 恢复 {} 个 Exporter，重建 {} 个 Prometheus target", restored, targetsAdded);
        } catch (IOException e) {
            log.error("加载 exporters.json 失败: {}", e.getMessage());
        }
    }

    // ==================== 模板管理 ====================

    @Override
    public List<ExporterTemplate> listTemplates() {
        return templateRegistry.listAll();
    }

    @Override
    public ExporterTemplate getTemplate(String type) {
        ExporterTemplate template = templateRegistry.get(type);
        if (template == null) {
            throw new IllegalArgumentException("未知的 Exporter 类型: " + type
                    + "，可用类型: " + templateRegistry.listAll().stream()
                    .map(ExporterTemplate::getType).toList());
        }
        return template;
    }

    // ==================== Exporter 生命周期 ====================

    @Override
    public ExporterInstance registerExporter(ExporterRegisterRequest request) {
        exporterLock.lock();
        try {
            return registerExporterInternal(request);
        } finally {
            exporterLock.unlock();
        }
    }

    private ExporterInstance registerExporterInternal(ExporterRegisterRequest request) {
        String type = request.getType();
        ExporterTemplate template = getTemplate(type);

        int hostPort = request.getMetricsPort() != null
                ? request.getMetricsPort() : template.getMetricsPort();
        String displayName = request.getDisplayName() != null
                ? request.getDisplayName() : template.getDisplayName();
        String serverId = request.getServerId() != null
                ? request.getServerId() : "local";
        String targetAddress = request.getTargetAddress();

        // 根据 serverId 决定 ID 前缀，避免冲突
        String exporterId = serverId + "-" + type + "-" + hostPort;
        boolean isRemote = !"local".equals(serverId);

        // BUG-3修复：验证 serverId 真实存在（防止向不存在的服务器注册 Exporter）
        if (isRemote) {
            try {
                serverService.getServer(serverId); // 若不存在会抛 IllegalArgumentException
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "无法注册 Exporter：服务器 '" + serverId + "' 不存在，请先添加服务器节点");
            }
        }

        // BUG-10修复：本地模式注册前检查端口是否已被占用（避免 Docker 端口冲突）
        if (!isRemote) {
            boolean portConflict = exporterInstances.values().stream()
                    .anyMatch(e -> e.isManagedByDocker() && e.getMetricsPort() == hostPort);
            if (portConflict) {
                throw new IllegalArgumentException(
                        "端口 " + hostPort + " 已被其他本地 Exporter 占用，请更换端口（metricsPort 参数）");
            }
        }

        if (exporterInstances.containsKey(exporterId)) {
            throw new IllegalArgumentException("Exporter 已注册: " + exporterId);
        }

        log.info("注册 Exporter: id={}, type={}, serverId={}, remote={}, target={}",
                exporterId, type, serverId, isRemote, targetAddress);

        String containerId = null;
        String containerName = null;
        String scrapeTarget;

        // FIX-REMOTE-EXPORTER:
        // node/windows 类型在远程机器上自行运行，OpsMonitor 只注册 Prometheus target
        // nginx/postgres/redis/mysql 等中间件/数据库类型：
        //   即使服务在远程机器，exporter 容器也在本机 Docker 启动，连接远程地址
        //   这样 Prometheus 只需抓取本机容器端口，无需跨网络访问远程 exporter 端口
        boolean needsLocalContainer = isRemote && !template.getImage().isEmpty()
                && !"node".equals(type) && !"windows".equals(type);

        if (isRemote && !needsLocalContainer) {
            // ===== 纯远程模式（node/windows）：不创建容器，直接注册 Prometheus target =====
            String remoteHost = null;
            if (targetAddress != null && !targetAddress.isBlank()) {
                remoteHost = extractHostPort(targetAddress, hostPort);
            } else {
                try {
                    ServerNode serverNode = serverService.getServer(serverId);
                    if (serverNode.getHost() != null && !serverNode.getHost().isBlank()) {
                        remoteHost = serverNode.getHost() + ":" + hostPort;
                        log.info("远程 Exporter targetAddress 为空，从 ServerNode 取 host: {}", remoteHost);
                    }
                } catch (Exception e) {
                    log.warn("无法从 ServerNode 获取 host (serverId={}): {}", serverId, e.getMessage());
                }
            }
            if (remoteHost == null) {
                throw new IllegalArgumentException(
                        "远程 Exporter 注册失败：targetAddress 为空且 serverId=" + serverId + " 对应的服务器无有效 host");
            }
            scrapeTarget = remoteHost;
            containerId = "remote";
            containerName = "remote-" + exporterId;

        } else if (needsLocalContainer) {
            // ===== 远程服务 + 本地容器模式（nginx/postgres/redis 等）=====
            // exporter 容器在本机 Docker 启动，连接远程服务地址
            // Prometheus 抓取本机容器端口，解决跨网络访问问题
            log.info("远程服务 {} 使用本地 Docker 容器模式，连接远程地址: {}", type, targetAddress);

            containerName = template.getContainerNamePrefix() + "-" + serverId.replace("-", "") + "-" + hostPort;

            // 构建环境变量（连接远程地址）
            Map<String, String> autoEnv = buildEnvFromTargetAddress(type, targetAddress);
            if (request.getEnv() != null) {
                autoEnv.putAll(request.getEnv());
            }

            containerId = createAndStartContainer(template, containerName, hostPort, exporterId, autoEnv);
            // Prometheus 通过容器名（Docker 网络内）抓取
            scrapeTarget = containerName + ":" + template.getContainerPort();

        } else {
            // ===== 本地模式：创建 Docker 容器 =====
            containerName = template.getContainerNamePrefix() + "-" + hostPort;

            Map<String, String> autoEnv = buildEnvFromTargetAddress(type, targetAddress);
            if (request.getEnv() != null) {
                autoEnv.putAll(request.getEnv());
            }

            containerId = createAndStartContainer(
                    template, containerName, hostPort, exporterId, autoEnv);
            scrapeTarget = containerName + ":" + template.getContainerPort();
        }

        // 注册到 Prometheus — labels 含 server_name/project/service 供 Grafana 筛选
        String serverName = serverId;
        try {
            ServerNode node = serverService.getServer(serverId);
            serverName = node.getName();
        } catch (Exception ignored) {}

        String project = request.getProject() != null ? request.getProject() : "";
        String service = request.getService() != null ? request.getService() : "";
        String environment = request.getEnvironment() != null ? request.getEnvironment() : "";

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("exporter_type", type);
        labels.put("managed_by", "ops-monitor");
        labels.put("server_id", serverId);
        labels.put("server_name", serverName);
        if (!project.isBlank()) labels.put("project", project);
        if (!service.isBlank()) labels.put("service", service);
        if (!environment.isBlank()) labels.put("env", environment);
        prometheusManager.addScrapeTarget(
                exporterId,
                List.of(scrapeTarget), labels);

        // 热加载
        // BUG-5修复：远程 Exporter reload 失败不应 throw，仅记录状态；
        // 本地 Exporter reload 失败则回滚并 throw
        boolean reloaded = false;
        try {
            reloaded = prometheusManager.reloadConfig();
        } catch (Exception e) {
            if (isRemote) {
                // 远程 Exporter：Prometheus 可能尚未启动，target 已写入文件，待 Prometheus 启动后自动生效
                // 不回滚，不 throw，仅标记 registeredInPrometheus=false
                log.warn("远程 Exporter Prometheus 热加载失败（已保留注册，不回滚，debounce 将重试）: {}", e.getMessage());
                reloaded = false; // 明确标记，后续 reloadService.requestReload() 会重试
            } else {
                // 本地 Exporter：回滚并 throw
                log.error("本地 Exporter Prometheus 热加载失败，执行回滚: {}", e.getMessage());
                try { prometheusManager.removeScrapeTarget(exporterId); } catch (Exception ignored2) {}
                if (containerId != null) {
                    try { dockerClient.stopContainerCmd(containerId).withTimeout(3).exec(); } catch (Exception ignored2) {}
                    try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception ignored2) {}
                }
                throw new RuntimeException("Exporter 注册失败（已回滚）: " + e.getMessage(), e);
            }
        }

        // P0-4: env NPE 防护
        Map<String, String> safeEnv = isRemote ? Map.of()
                : (request.getEnv() != null ? request.getEnv() : Map.of());

        ExporterInstance instance = ExporterInstance.builder()
                .id(exporterId)
                .type(type)
                .displayName(displayName)
                .serverId(serverId)
                .serverName(serverName)   // 修复：正确填充服务器名称，供 PrometheusTargetWriter 写入 server_name label
                .targetAddress(targetAddress)
                .project(project)
                .service(service)
                .environment(environment)
                .containerId((isRemote && !needsLocalContainer) ? "remote" : containerId.substring(0, 12))
                .containerName(containerName)
                .state((isRemote && !needsLocalContainer) ? "remote" : "running")
                .metricsPort(hostPort)
                .jobName(exporterId)
                .scrapeTarget(scrapeTarget)
                .env(safeEnv)
                .registeredInPrometheus(reloaded)
                .managedByDocker(!isRemote || needsLocalContainer)  // FIX: 远程+本地容器模式也需管理
                .build();

        exporterInstances.put(exporterId, instance);
        saveExporters();
        // 写 File SD targets
        targetWriter.writeAllTargets(exporterInstances.values());
        // 9G-1: debounce reload 避免风暴
        reloadService.requestReload();
        log.info("Exporter 注册成功: {} (remote={})", exporterId, isRemote);

        // 通知 GrafanaSyncService 重建仪表盘（异步）
        if (grafanaSyncService != null) {
            final String sid   = serverId;
            final String etype = type;
            OPS_ASYNC_POOL.submit(() -> grafanaSyncService.onExporterRegistered(sid, etype));
        }
        return instance;
    }

    /**
     * 从地址中提取 host:port 格式
     * 支持: redis://host:6379 → host:exporterPort
     *       host:3306 → host:exporterPort
     *       http://host/path → host:exporterPort
     */
    private String extractHostPort(String address, int exporterPort) {
        String host = address;
        // 去掉协议前缀
        if (host.contains("://")) {
            host = host.substring(host.indexOf("://") + 3);
        }
        // 去掉路径
        if (host.contains("/")) {
            host = host.substring(0, host.indexOf("/"));
        }
        // 去掉端口（用 exporter 端口替换）
        if (host.contains(":")) {
            host = host.substring(0, host.indexOf(":"));
        }
        return host + ":" + exporterPort;
    }

    @Override
    public void unregisterExporter(String exporterId) {
        exporterLock.lock();
        try {
            unregisterExporterInternal(exporterId);
        } finally {
            exporterLock.unlock();
        }
    }

    private void unregisterExporterInternal(String exporterId) {
        ExporterInstance instance = getExporter(exporterId);

        log.info("注销 Exporter: {} (managedByDocker={})", exporterId, instance.isManagedByDocker());

        if (instance.isManagedByDocker()) {
            stopAndRemoveContainer(instance.getContainerName());
        }

        prometheusManager.removeScrapeTarget(instance.getJobName());
        try {
            prometheusManager.reloadConfig();
        } catch (Exception e) {
            log.warn("注销后 Prometheus 热加载失败: {}", e.getMessage());
        }

        exporterInstances.remove(exporterId);
        saveExporters();
        targetWriter.writeAllTargets(exporterInstances.values());
        // 9G-1: debounce reload
        reloadService.requestReload();
        log.info("Exporter 注销完成: {}", exporterId);

        // 通知 GrafanaSyncService 重建仪表盘（异步，不阻塞注销流程）
        if (grafanaSyncService != null) {
            final String sid   = instance.getServerId();
            final String etype = instance.getType();
            OPS_ASYNC_POOL.submit(() -> grafanaSyncService.onExporterUnregistered(sid, etype));
        }

        // FIX-VM-CLEANUP: 异步清理 VictoriaMetrics 历史 series，防止 Grafana 显示已删除的服务
        // 注销后立即发起删除，VictoriaMetrics admin API 会标记 series 为删除状态
        final String cleanupJobName = exporterId;
        final String vmUrl = properties.getVictoria().getUrl();
        OPS_ASYNC_POOL.submit(() -> deleteVictoriaMetricsSeries(cleanupJobName, vmUrl));
    }

    /**
     * FIX-VM-CLEANUP: 删除 VictoriaMetrics 中指定 job 的所有历史 series
     *
     * 调用 VictoriaMetrics admin API：DELETE /api/v1/admin/tsdb/delete_series
     * match[] 参数过滤 job_name 匹配的所有 series（含 up 指标、业务指标等）
     *
     * 注意：删除操作是异步标记，VM 在下次合并时实际清理数据
     * 调用后 Grafana 的 label_values() 查询将不再返回已删除的 exporter
     */
    private void deleteVictoriaMetricsSeries(String jobName, String vmUrl) {
        try {
            // 通过 job_name 匹配所有 series（Prometheus 的 job label）
            String match = "{job=\"" + jobName + "\"}";
            String encodedMatch = java.net.URLEncoder.encode(match, java.nio.charset.StandardCharsets.UTF_8);
            String deleteUrl = vmUrl + "/api/v1/admin/tsdb/delete_series?match[]=" + encodedMatch;

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(deleteUrl))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                log.info("[VM-Cleanup] 已删除 VictoriaMetrics series: job={}", jobName);
            } else {
                log.warn("[VM-Cleanup] 删除 series 响应异常: status={}, body={}",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("[VM-Cleanup] 清理 VictoriaMetrics series 失败（不影响注销）: {}", e.getMessage());
        }
    }

    @Override
    public void startExporter(String exporterId) {
        ExporterInstance instance = getExporter(exporterId);
        if (!instance.isManagedByDocker()) {
            throw new IllegalArgumentException("远程 Exporter 不支持此操作，请在目标服务器上手动启动");
        }
        log.info("启动 Exporter: {}", exporterId);
        dockerClient.startContainerCmd(instance.getContainerName()).exec();
        instance.setState("running");
    }

    @Override
    public void stopExporter(String exporterId) {
        ExporterInstance instance = getExporter(exporterId);
        if (!instance.isManagedByDocker()) {
            throw new IllegalArgumentException("远程 Exporter 不支持此操作，请在目标服务器上手动停止");
        }
        log.info("停止 Exporter: {}", exporterId);
        dockerClient.stopContainerCmd(instance.getContainerName()).withTimeout(5).exec();
        instance.setState("exited");
    }

    // ==================== 查询 ====================

    @Override
    public List<ExporterInstance> listExporters() {
        // 修复：原实现对每个 Exporter 单独调用 inspectContainerCmd，N+1 问题
        // 改为：一次 listContainersCmd 批量获取所有容器状态，再更新内存
        try {
            Map<String, String> containerStates = new java.util.HashMap<>();
            dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(LABEL_MANAGED, "true"))
                    .exec()
                    .forEach(c -> {
                        if (c.getNames() != null && c.getNames().length > 0) {
                            String name = c.getNames()[0].replaceFirst("^/", "");
                            containerStates.put(name, c.getState());
                        }
                    });

            for (ExporterInstance inst : exporterInstances.values()) {
                if (!inst.isManagedByDocker()) continue; // 远程 Exporter 跳过
                String state = containerStates.get(inst.getContainerName());
                inst.setState(state != null ? state : "unknown");
            }
        } catch (Exception e) {
            // Docker 不可用时不影响返回（状态保持上次缓存值）
            log.debug("[ExporterManager] 批量同步容器状态失败（Docker不可用）: {}", e.getMessage());
        }
        return new ArrayList<>(exporterInstances.values());
    }

    /**
     * v2.13-A: Agent 级联状态更新
     * 遍历所有 Exporter，将匹配 serverId 的实例标记 agentStatus
     * 变更后自动持久化到 exporters.json
     */
    @Override
    public void cascadeAgentStatus(String serverId, String agentStatus) {
        boolean changed = false;
        for (ExporterInstance exp : exporterInstances.values()) {
            if (serverId.equals(exp.getServerId())) {
                exp.setAgentStatus(agentStatus);
                changed = true;
                log.info("Exporter {} ({}) agentStatus → {}", exp.getId(), exp.getType(), agentStatus);
            }
        }
        if (changed) {
            saveExporters();
            log.info("已级联更新 serverId={} 的 agentStatus={}", serverId, agentStatus);
        }
    }

    /**
     * v2.17: 更新 Exporter 标签
     * 变更后自动持久化到 exporters.json
     */
    @Override
    public void updateLabels(String exporterId, String project, String service) {
        ExporterInstance exp = getExporter(exporterId);
        exp.setProject(project);
        exp.setService(service);
        saveExporters();
        log.info("Exporter {} 标签已更新: project={}, service={}", exporterId, project, service);
    }

    public ExporterInstance getExporter(String exporterId) {
        ExporterInstance instance = exporterInstances.get(exporterId);
        if (instance == null) {
            throw new IllegalArgumentException("Exporter 不存在: " + exporterId);
        }
        return instance;
    }

    // ==================== 重启恢复 ====================

    /**
     * 从 Docker 容器标签恢复已管理的 Exporter 状态
     * 在系统重启后由 SystemInitializer 调用
     */
    public void rebuildManagedExportersFromDocker() {
        // 先从 JSON 恢复所有 Exporter（含远程）
        loadExportersFromJson();

        log.info("扫描 Docker 容器，恢复托管 Exporter 状态...");
        int recovered = 0;

        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(LABEL_MANAGED, "true"))
                    .exec();

            for (Container container : containers) {
                try {
                    Map<String, String> containerLabels = container.getLabels();
                    if (containerLabels == null) continue;

                    String type = containerLabels.get(LABEL_TYPE);
                    String exporterId = containerLabels.get(LABEL_EXPORTER_ID);
                    String jobName = containerLabels.get(LABEL_JOB_NAME);
                    String portStr = containerLabels.get(LABEL_METRICS_PORT);

                    if (type == null || exporterId == null || jobName == null) {
                        log.debug("跳过不完整标签的容器: {}", container.getId());
                        continue;
                    }

                    // 已在缓存中则跳过
                    if (exporterInstances.containsKey(exporterId)) continue;

                    String containerName = "-";
                    if (container.getNames() != null && container.getNames().length > 0) {
                        containerName = container.getNames()[0].replaceFirst("^/", "");
                    }

                    int metricsPort = 0;
                    try { metricsPort = Integer.parseInt(portStr); } catch (Exception ignored) {}

                    ExporterTemplate template = templateRegistry.get(type);
                    String scrapeTarget = containerName + ":"
                            + (template != null ? template.getContainerPort() : metricsPort);
                    String displayName = template != null ? template.getDisplayName() : type;

                    ExporterInstance instance = ExporterInstance.builder()
                            .id(exporterId)
                            .type(type)
                            .displayName(displayName)
                            .containerId(container.getId().substring(0, 12))
                            .containerName(containerName)
                            .state(container.getState())
                            .metricsPort(metricsPort)
                            .jobName(jobName)
                            .scrapeTarget(scrapeTarget)
                            .env(Map.of())
                            .registeredInPrometheus(true)
                            .build();

                    exporterInstances.put(exporterId, instance);
                    recovered++;
                    log.info("已恢复 Exporter: {} ({}) [{}]", exporterId, containerName, container.getState());

                } catch (Exception e) {
                    log.warn("恢复单个 Exporter 异常: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("扫描 Docker 容器失败: {}", e.getMessage());
        }

        log.info("Exporter 状态恢复完成，共恢复 {} 个", recovered);
    }

    // ==================== 私有方法 ====================

    /**
     * 创建并启动 Exporter Docker 容器
     * - 自动 pull 镜像（如果不存在）
     * - 创建前检查同名容器是否存在
     * - 添加管理标签 ops.monitor.*
     */
    private String createAndStartContainer(ExporterTemplate template,
                                           String containerName,
                                           int hostPort,
                                           String exporterId,
                                           Map<String, String> userEnv) {
        // Step 0: 确保镜像存在，不存在则自动拉取
        ensureImageExists(template.getImage());

        // 检查同名容器是否已存在
        if (containerExists(containerName)) {
            log.warn("同名容器已存在: {}，先移除", containerName);
            stopAndRemoveContainer(containerName);
        }

        // 合并环境变量（_CMD_ARGS 是内部控制 key，不作为容器环境变量传递）
        List<String> envList = new ArrayList<>();
        if (template.getDefaultEnv() != null) {
            template.getDefaultEnv().entrySet().stream()
                    .filter(e -> !"_CMD_ARGS".equals(e.getKey()))
                    .forEach(e -> envList.add(e.getKey() + "=" + e.getValue()));
        }
        if (userEnv != null) {
            userEnv.entrySet().stream()
                    .filter(e -> !"_CMD_ARGS".equals(e.getKey()))
                    .forEach(e -> envList.add(e.getKey() + "=" + e.getValue()));
        }

        // 管理标签
        Map<String, String> containerLabels = new LinkedHashMap<>();
        containerLabels.put(LABEL_MANAGED, "true");
        containerLabels.put(LABEL_TYPE, template.getType());
        containerLabels.put(LABEL_JOB_NAME, template.getJobName());
        containerLabels.put(LABEL_METRICS_PORT, String.valueOf(hostPort));
        containerLabels.put(LABEL_EXPORTER_ID, exporterId);

        // 端口绑定
        ExposedPort exposedPort = ExposedPort.tcp(template.getContainerPort());
        Ports portBindings = new Ports();
        portBindings.bind(exposedPort, Ports.Binding.bindPort(hostPort));

        // 创建容器
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withNetworkMode("ops-monitor_ops-network")
                .withRestartPolicy(RestartPolicy.unlessStoppedRestart());

        // FIX-CMD: 支持 ExporterTemplate.command 参数（如 nginx-prometheus-exporter 用 --nginx.scrape-uri）
        var createCmd = dockerClient.createContainerCmd(template.getImage())
                .withName(containerName)
                .withLabels(containerLabels)
                .withExposedPorts(exposedPort)
                .withHostConfig(hostConfig)
                .withEnv(envList);

        // 如果 template 有 command 参数，注入环境变量中特殊参数作为容器 cmd
        if (template.getCommand() != null && !template.getCommand().isEmpty()) {
            createCmd = createCmd.withCmd(template.getCommand().toArray(new String[0]));
        }
        // 如果 userEnv 里有 _CMD_ARGS，解析为容器启动命令参数
        // 格式：单个参数直接用（如 "--nginx.scrape-uri=http://..."），
        //       多个参数以 "|||" 分隔（避免 URL 中可能含空格的问题）
        if (userEnv != null && userEnv.containsKey("_CMD_ARGS")) {
            String rawArgs = userEnv.get("_CMD_ARGS");
            String[] cmdArgs = rawArgs.contains("|||")
                    ? rawArgs.split("\\|\\|\\|")
                    : new String[]{rawArgs};
            createCmd = createCmd.withCmd(cmdArgs);
            log.info("[Container] 容器启动命令参数: {}", java.util.Arrays.toString(cmdArgs));
        }

        CreateContainerResponse container = createCmd.exec();

        String containerId = container.getId();
        log.info("容器已创建: {} ({}) labels={}", containerName, containerId.substring(0, 12), containerLabels);

        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();
        log.info("容器已启动: {}", containerName);

        return containerId;
    }

    /**
     * 检查容器是否存在（按名称）
     */
    private boolean containerExists(String containerName) {
        try {
            dockerClient.inspectContainerCmd(containerName).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 停止并删除容器（按名称，忽略异常）
     */
    private void stopAndRemoveContainer(String containerName) {
        try {
            dockerClient.stopContainerCmd(containerName).withTimeout(5).exec();
        } catch (Exception e) {
            log.debug("停止容器异常（可能已停止）: {}", e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
            log.debug("已移除容器: {}", containerName);
        } catch (Exception e) {
            log.warn("移除容器失败: {}", e.getMessage());
        }
    }

    /**
     * 根据类型和目标地址自动构建环境变量
     * 用户只需提供地址，系统自动转换为 Exporter 需要的参数格式
     */
    private Map<String, String> buildEnvFromTargetAddress(String type, String targetAddress) {
        Map<String, String> env = new LinkedHashMap<>();
        if (targetAddress == null || targetAddress.isBlank()) return env;

        switch (type) {
            case "redis":
                env.put("REDIS_ADDR", targetAddress);
                break;
            case "mysql":
                // mysqld_exporter 需要 DATA_SOURCE_NAME 格式: user:pass@tcp(host:port)/
                if (!targetAddress.contains("@")) {
                    env.put("DATA_SOURCE_NAME", "exporter:exporter@tcp(" + targetAddress + ")/");
                } else {
                    env.put("DATA_SOURCE_NAME", targetAddress);
                }
                break;
            case "postgres":
            case "kingbase":
                if (targetAddress.startsWith("postgresql://") || targetAddress.contains("@")) {
                    env.put("DATA_SOURCE_NAME", targetAddress);
                } else {
                    env.put("DATA_SOURCE_NAME",
                            "postgresql://exporter:exporter@" + targetAddress + "/postgres?sslmode=disable");
                }
                break;
            case "oracle":
                env.put("DATA_SOURCE_NAME", targetAddress);
                break;
            case "nginx":
                // FIX-NGINX-CMD: nginx-prometheus-exporter 1.x 通过命令行参数更可靠
                // 使用 _CMD_ARGS 特殊 key，createAndStartContainer 会解析为容器启动参数
                env.put("_CMD_ARGS", "--nginx.scrape-uri=" + targetAddress);
                break;
            case "geoserver":
                env.put("JMX_URL", targetAddress);
                break;
            default:
                log.warn("未知类型 {}，跳过自动参数构建", type);
        }
        return env;
    }

    /**
     * 确保 Docker 镜像存在，不存在则自动拉取
     * 阻塞等待 pull 完成
     */
    private void ensureImageExists(String imageName) {
        try {
            dockerClient.inspectImageCmd(imageName).exec();
            log.debug("镜像已存在: {}", imageName);
            return;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            // 镜像不存在，继续拉取
        } catch (Exception e) {
            // 其他异常也尝试拉取
            log.debug("检查镜像异常，尝试拉取: {}", e.getMessage());
        }

        log.info("镜像不存在，正在自动拉取: {} (请耐心等待...)", imageName);
        try {
            dockerClient.pullImageCmd(imageName)
                    .start()
                    .awaitCompletion();
            log.info("镜像拉取完成: {}", imageName);
        } catch (Exception e) {
            throw new RuntimeException("镜像拉取失败: " + imageName + " — " + e.getMessage(), e);
        }
    }
    /**
     * v2.10 P1-07 修复:注册线程池关闭钩子,避免 Spring DevTools 热重启/多次 @SpringBootTest 累积 → OOM
     */
    @PreDestroy
    public void shutdownThreadPool_v210() {
        try {
            if (OPS_ASYNC_POOL != null && !OPS_ASYNC_POOL.isShutdown()) {
                OPS_ASYNC_POOL.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
}