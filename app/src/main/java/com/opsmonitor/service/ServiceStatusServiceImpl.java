package com.opsmonitor.service;

import com.opsmonitor.core.StatusCache;
import com.opsmonitor.docker.DockerEnvironmentChecker;
import com.opsmonitor.docker.DockerService;
import com.opsmonitor.model.AggregatedStatus;
import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.monitor.ExporterManager;
import com.opsmonitor.monitor.GrafanaManager;
import com.opsmonitor.monitor.PrometheusManager;
import com.opsmonitor.monitor.PrometheusQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务状态聚合实现
 * 从 Prometheus API + Docker API 聚合系统全局状态
 *
 * 阶段6B 增强：集成 StatusCache，5 秒 TTL 避免频繁查询 Prometheus
 */
@Slf4j
@Service
public class ServiceStatusServiceImpl implements ServiceStatusService {

    private final PrometheusQueryService promQuery;
    private final PrometheusManager prometheusManager;
    private final GrafanaManager grafanaManager;
    private final DockerEnvironmentChecker dockerChecker;
    private final DockerService dockerService;
    private final StatusCache<AggregatedStatus> cache;
    private final ExporterManager exporterManager;

    /** BUG-HOST-DOWN 修复：注入主机可达性探测（可选，启动期/测试环境可为 null） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.opsmonitor.monitor.RemoteHostReachabilityProbe hostProbe;

    // PromQL 表达式 — 同时兼容 Linux(node_*) 和 Windows(windows_*)
    // FIX-CPU: 使用 irate()[2m] 替代 rate()[5m]，避免 counter reset 导致的负值
    //          clamp_min(..., 0) 兜底防止负值穿透到 UI 显示
    // FIX-DISK: 去掉 mountpoint="/" 限制（WSL环境根分区不一定在/），
    //           改为排除 tmpfs/overlay/squashfs，取最大值（最高占用的分区）
    private static final String CPU_QUERY =
            "clamp_min(100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\"}[2m])) * 100), 0)" +
                    " or " +
                    "clamp_min(100 - sum(rate(windows_cpu_time_total{mode=\"idle\"}[2m]))" +
                    " / sum(rate(windows_cpu_time_total{}[2m])) * 100, 0)";
    private static final String MEMORY_QUERY =
            "(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100" +
                    " or " +
                    "(1 - windows_os_physical_memory_free_bytes / windows_cs_physical_memory_bytes) * 100";
    // FIX-DISK: max() 取所有分区中占用最高的，不再限定 mountpoint="/"
    private static final String DISK_QUERY =
            "max((1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs|devtmpfs|efivarfs\"}" +
                    " / node_filesystem_size_bytes{fstype!~\"tmpfs|overlay|squashfs|devtmpfs|efivarfs\"})) * 100)" +
                    " or " +
                    "max((1 - windows_logical_disk_free_bytes{volume!~\"HarddiskVolume.*\"}" +
                    " / windows_logical_disk_size_bytes{volume!~\"HarddiskVolume.*\"}) * 100)";

    // v2.29: 刷新锁——确保同一时间只有 1 个线程执行 queryFresh()
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile AggregatedStatus lastGoodStatus = null;

    public ServiceStatusServiceImpl(PrometheusQueryService promQuery,
                                    PrometheusManager prometheusManager,
                                    GrafanaManager grafanaManager,
                                    DockerEnvironmentChecker dockerChecker,
                                    DockerService dockerService,
                                    ExporterManager exporterManager) {
        this.promQuery = promQuery;
        this.prometheusManager = prometheusManager;
        this.grafanaManager = grafanaManager;
        this.dockerChecker = dockerChecker;
        this.dockerService = dockerService;
        this.exporterManager = exporterManager;
        this.cache = new StatusCache<>();
    }

    @Override
    public AggregatedStatus getAggregatedStatus() {
        AggregatedStatus cached = cache.get();
        if (cached != null) {
            return cached;
        }

        // v2.29: 缓存过期——只允许 1 个线程去刷新，其余返回旧值
        if (!refreshing.compareAndSet(false, true)) {
            if (lastGoodStatus != null) return lastGoodStatus;
        }

        try {
            AggregatedStatus status = queryFresh();
            cache.put(status);
            lastGoodStatus = status;
            return status;
        } catch (Exception e) {
            log.warn("状态查询异常，返回降级结果: {}", e.getMessage());
            if (lastGoodStatus != null) return lastGoodStatus;
            return AggregatedStatus.builder()
                    .docker("UNKNOWN")
                    .prometheus("UNKNOWN")
                    .grafana("UNKNOWN")
                    .cpuUsage(-1)
                    .memoryUsage(-1)
                    .diskUsage(-1)
                    .containerTotal(0)
                    .containerRunning(0)
                    .services(java.util.Map.of())
                    .servicesByServer(java.util.Map.of())
                    .timestamp(System.currentTimeMillis())
                    .build();
        } finally {
            refreshing.set(false);
        }
    }

    private AggregatedStatus queryFresh() {
        boolean dockerOk = dockerChecker.isDockerAvailable();
        boolean promOk = prometheusManager.isRunning();
        boolean grafanaOk = grafanaManager.isRunning();

        // v2.29: 并行执行 3 个 PromQL 查询（原串行 3-9s → 并行 1-3s）
        var cpuFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> promQuery.queryScalar(CPU_QUERY).orElse(-1.0));
        var memFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> promQuery.queryScalar(MEMORY_QUERY).orElse(-1.0));
        var diskFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> promQuery.queryScalar(DISK_QUERY).orElse(-1.0));

        double cpu, mem, disk;
        try {
            var allDone = java.util.concurrent.CompletableFuture.allOf(cpuFuture, memFuture, diskFuture);
            allDone.get(5, java.util.concurrent.TimeUnit.SECONDS);
            cpu = cpuFuture.get();
            mem = memFuture.get();
            disk = diskFuture.get();
        } catch (Exception e) {
            log.debug("[StatusService] PromQL 并行查询超时: {}", e.getMessage());
            cpu = cpuFuture.getNow(-1.0);
            mem = memFuture.getNow(-1.0);
            disk = diskFuture.getNow(-1.0);
        }

        cpu = cpu >= 0 ? Math.round(cpu * 100.0) / 100.0 : -1;
        mem = mem >= 0 ? Math.round(mem * 100.0) / 100.0 : -1;
        disk = disk >= 0 ? Math.round(disk * 100.0) / 100.0 : -1;

        // 3. 容器统计
        int total = 0, running = 0;
        try {
            var allContainers = dockerService.listContainers(true);
            total = allContainers.size();
            running = (int) allContainers.stream()
                    .filter(c -> "running".equalsIgnoreCase(c.getState()))
                    .count();
        } catch (Exception e) {
            log.debug("获取容器统计异常: {}", e.getMessage());
        }

        // 4. 各 Job 状态
        Map<String, Integer> jobStatus = promQuery.queryAllJobStatus();
        Map<String, String> services = new LinkedHashMap<>();
        jobStatus.forEach((job, val) -> services.put(job, val == 1 ? "UP" : "DOWN"));

        // 5. 按服务器分组的服务状态
        // BUG-HOST-DOWN 修复：
        //   (a) 原来用 exp.getType() 作 Map key，同一台服务器注册多个同 type Exporter 会被覆盖
        //   (b) 原来完全信任 Prometheus up 指标，主机关机但本机 exporter 容器仍活着时
        //       up=1 → 蜂窝图误报"在线"。现融合 RemoteHostReachabilityProbe 强制翻转。
        Map<String, Map<String, String>> servicesByServer = new LinkedHashMap<>();
        try {
            for (ExporterInstance exp : exporterManager.listExporters()) {
                String sid = exp.getServerId() != null ? exp.getServerId() : "local";
                String jobName = exp.getJobName();
                // P1-2: 区分 PENDING（Prometheus 还未 scrape）和 DOWN
                String upDown;
                if (services.containsKey(jobName)) {
                    upDown = services.get(jobName);
                } else {
                    upDown = "PENDING";
                }

                // BUG-HOST-DOWN 关键修复：主机不可达 → 强制 DOWN
                // 即使 Prometheus 报告 up=1（因 exporter 容器在本机跑），也不信任
                if (hostProbe != null && !hostProbe.isReachable(sid)) {
                    upDown = "DOWN";
                }

                // 使用 exporterId 作 key（而非 type），避免同 type 多实例互相覆盖
                servicesByServer.computeIfAbsent(sid, k -> new LinkedHashMap<>())
                        .put(exp.getId(), upDown);
            }
        } catch (Exception e) {
            log.debug("构建 servicesByServer 异常: {}", e.getMessage());
        }

        return AggregatedStatus.builder()
                .docker(dockerOk ? "UP" : "DOWN")
                .prometheus(promOk ? "UP" : "DOWN")
                .grafana(grafanaOk ? "UP" : "DOWN")
                .cpuUsage(cpu)
                .memoryUsage(mem)
                .diskUsage(disk)
                .containerTotal(total)
                .containerRunning(running)
                .services(services)
                .servicesByServer(servicesByServer)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}