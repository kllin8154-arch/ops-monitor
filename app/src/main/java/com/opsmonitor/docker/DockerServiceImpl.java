package com.opsmonitor.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.opsmonitor.model.ContainerInfo;
import com.opsmonitor.model.ContainerLogResponse;
import com.opsmonitor.model.ContainerStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Docker 服务实现
 * 所有操作通过 docker-java SDK，不使用 shell 命令
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerServiceImpl implements DockerService {

    private final DockerClient dockerClient;

    /** 受保护容器名称前缀（不允许删除） */
    private static final Set<String> PROTECTED_PREFIXES = Set.of(
            "ops-prometheus", "ops-grafana", "ops-node-exporter"
    );

    // ==================== 容器列表与查询 ====================

    @Override
    public List<ContainerInfo> listContainers(boolean all) {
        return listContainers(all, null, null);
    }

    @Override
    public List<ContainerInfo> listContainers(boolean all, String status, String name) {
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(all)
                    .exec();

            return containers.stream()
                    .map(this::toContainerInfo)
                    .filter(c -> {
                        if (status != null && !status.isBlank()) {
                            if (!c.getState().equalsIgnoreCase(status)) {
                                return false;
                            }
                        }
                        if (name != null && !name.isBlank()) {
                            return c.getName().toLowerCase().contains(name.toLowerCase());
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(ContainerInfo::getState)
                            .thenComparing(ContainerInfo::getName))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[Docker] 获取容器列表失败: {}", e.getMessage());
            return List.of(); // v2.29: 返回空列表而非抛 500，避免阻塞前端
        }
    }

    @Override
    public ContainerInfo getContainer(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return toContainerInfoFromInspect(inspect);
        } catch (Exception e) {
            log.error("获取容器详情失败 [{}]: {}", containerId, e.getMessage());
            throw new RuntimeException("容器不存在或无法访问: " + containerId, e);
        }
    }

    // ==================== 容器生命周期控制 ====================

    @Override
    public void startContainer(String containerId) {
        validateContainerExists(containerId);
        log.info("启动容器: {}", containerId);
        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("启动容器失败 [{}]: {}", containerId, e.getMessage());
            throw new RuntimeException("启动容器失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stopContainer(String containerId, int timeout) {
        validateContainerExists(containerId);
        log.info("停止容器: {}, 超时: {}s", containerId, timeout);
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(timeout)
                    .exec();
        } catch (Exception e) {
            log.error("停止容器失败 [{}]: {}", containerId, e.getMessage());
            throw new RuntimeException("停止容器失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void restartContainer(String containerId) {
        validateContainerExists(containerId);
        log.info("重启容器: {}", containerId);
        try {
            dockerClient.restartContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("重启容器失败 [{}]: {}", containerId, e.getMessage());
            throw new RuntimeException("重启容器失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeContainer(String containerId, boolean force) {
        validateContainerExists(containerId);

        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            String name = inspect.getName().replaceFirst("^/", "");
            if (isProtectedContainer(name)) {
                throw new IllegalArgumentException("不允许删除受保护的系统容器: " + name);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // ignore inspect error
        }

        log.info("删除容器: {}, 强制: {}", containerId, force);
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(force)
                    .withRemoveVolumes(false)
                    .exec();
        } catch (Exception e) {
            log.error("删除容器失败 [{}]: {}", containerId, e.getMessage());
            throw new RuntimeException("删除容器失败: " + e.getMessage(), e);
        }
    }

    // ==================== 日志 ====================

    @Override
    public ContainerLogResponse getLogs(String containerId, int tail,
                                        boolean stdout, boolean stderr, boolean timestamps) {
        validateContainerExists(containerId);

        StringBuilder logBuilder = new StringBuilder();
        List<ContainerLogResponse.LogLine> lines = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(stdout)
                    .withStdErr(stderr)
                    .withTail(tail)
                    .withTimestamps(timestamps)
                    .withFollowStream(false)
                    .exec(new ResultCallback<Frame>() {
                        @Override
                        public void onStart(Closeable closeable) {}

                        @Override
                        public void onNext(Frame frame) {
                            String payload = new String(frame.getPayload());
                            logBuilder.append(payload);

                            String streamType = frame.getStreamType() == StreamType.STDERR ? "stderr" : "stdout";
                            for (String line : payload.split("\n")) {
                                if (line.isEmpty()) continue;
                                String ts = "";
                                String content = line;
                                if (timestamps && line.length() > 30 && line.charAt(4) == '-') {
                                    int spaceIdx = line.indexOf(' ');
                                    if (spaceIdx > 0) {
                                        ts = line.substring(0, spaceIdx);
                                        content = line.substring(spaceIdx + 1);
                                    }
                                }
                                lines.add(ContainerLogResponse.LogLine.builder()
                                        .timestamp(ts)
                                        .stream(streamType)
                                        .content(content)
                                        .build());
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            log.error("读取日志出错 [{}]: {}", containerId, throwable.getMessage());
                            latch.countDown();
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }

                        @Override
                        public void close() {
                            latch.countDown();
                        }
                    });

            boolean completed = latch.await(15, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("获取日志超时 [{}]", containerId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取日志被中断 [{}]", containerId);
        }

        String containerName = resolveContainerName(containerId);

        return ContainerLogResponse.builder()
                .containerId(containerId)
                .containerName(containerName)
                .lineCount(lines.size())
                .logs(logBuilder.toString())
                .lines(lines)
                .stdout(stdout)
                .stderr(stderr)
                .build();
    }

    // ==================== 资源监控 ====================

    /**
     * 获取容器资源统计（短时采样，不阻塞）
     * 使用 noStream=true，拿到一次采样后立即返回
     *
     * 修复点：
     * 1. await 返回 boolean，正确处理超时
     * 2. stats 为 null 时返回零值对象而非抛异常
     */
    @Override
    public ContainerStats getStats(String containerId) {
        validateContainerExists(containerId);

        final Statistics[] statsHolder = new Statistics[1];
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            dockerClient.statsCmd(containerId)
                    .withNoStream(true)
                    .exec(new ResultCallback<Statistics>() {
                        @Override
                        public void onStart(Closeable closeable) {}

                        @Override
                        public void onNext(Statistics statistics) {
                            statsHolder[0] = statistics;
                            latch.countDown();
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            log.error("获取 stats 出错 [{}]: {}", containerId, throwable.getMessage());
                            latch.countDown();
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }

                        @Override
                        public void close() {
                            // noStream 模式下 onComplete 会触发，这里不重复 countDown
                        }
                    });

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("获取 stats 超时 [{}]，已等待 10 秒", containerId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取 stats 被中断 [{}]", containerId);
        }

        // stats 为 null：超时或出错，返回零值
        if (statsHolder[0] == null) {
            String containerName = resolveContainerName(containerId);
            return ContainerStats.builder()
                    .containerId(containerId)
                    .containerName(containerName)
                    .cpuPercent(0.0)
                    .memoryUsed(0L)
                    .memoryLimit(0L)
                    .memoryPercent(0.0)
                    .memoryUsedFormatted("0 B")
                    .memoryLimitFormatted("0 B")
                    .networkRx(0L)
                    .networkTx(0L)
                    .networkRxFormatted("0 B")
                    .networkTxFormatted("0 B")
                    .blockRead(0L)
                    .blockWrite(0L)
                    .pids(0L)
                    .build();
        }

        return calculateStats(containerId, statsHolder[0]);
    }

    // ==================== 工具方法 ====================

    @Override
    public boolean containerExists(String containerId) {
        try {
            dockerClient.inspectContainerCmd(containerId).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isContainerRunning(String containerNameOrId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerNameOrId).exec();
            Boolean running = inspect.getState().getRunning();
            return running != null && running;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isProtectedContainer(String containerName) {
        if (containerName == null) return false;
        String clean = containerName.replaceFirst("^/", "").toLowerCase();
        return PROTECTED_PREFIXES.stream().anyMatch(clean::startsWith);
    }

    // ==================== 私有方法 ====================

    private void validateContainerExists(String containerId) {
        if (!containerExists(containerId)) {
            throw new IllegalArgumentException("容器不存在: " + containerId);
        }
    }

    /**
     * 获取容器名称，失败则返回 containerId
     */
    private String resolveContainerName(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return inspect.getName().replaceFirst("^/", "");
        } catch (Exception e) {
            return containerId;
        }
    }

    /**
     * Container -> ContainerInfo 转换
     */
    private ContainerInfo toContainerInfo(Container c) {
        String name = "-";
        if (c.getNames() != null && c.getNames().length > 0) {
            name = c.getNames()[0].replaceFirst("^/", "");
        }

        List<ContainerInfo.PortMapping> portMappings = new ArrayList<>();
        if (c.getPorts() != null) {
            for (ContainerPort p : c.getPorts()) {
                portMappings.add(ContainerInfo.PortMapping.builder()
                        .ip(p.getIp())
                        .privatePort(p.getPrivatePort())
                        .publicPort(p.getPublicPort())
                        .type(p.getType())
                        .build());
            }
        }

        String networkMode = "";
        if (c.getHostConfig() != null) {
            networkMode = c.getHostConfig().getNetworkMode();
        }

        return ContainerInfo.builder()
                .id(c.getId().substring(0, 12))
                .fullId(c.getId())
                .name(name)
                .image(c.getImage())
                .status(c.getStatus())
                .state(c.getState())
                .ports(portMappings)
                .created(c.getCreated())
                .networkMode(networkMode)
                .protectedContainer(isProtectedContainer(name))
                .cpuUsage(null)
                .memoryUsage(null)
                .memoryLimit(null)
                .build();
    }

    /**
     * InspectContainerResponse -> ContainerInfo 转换（详情接口用）
     */
    private ContainerInfo toContainerInfoFromInspect(InspectContainerResponse inspect) {
        String name = inspect.getName().replaceFirst("^/", "");
        String image = inspect.getConfig() != null ? inspect.getConfig().getImage() : "";

        List<ContainerInfo.PortMapping> portMappings = new ArrayList<>();
        if (inspect.getNetworkSettings() != null && inspect.getNetworkSettings().getPorts() != null) {
            Ports ports = inspect.getNetworkSettings().getPorts();
            Map<ExposedPort, Ports.Binding[]> bindings = ports.getBindings();
            if (bindings != null) {
                bindings.forEach((exposedPort, bindingArr) -> {
                    if (bindingArr != null) {
                        for (Ports.Binding binding : bindingArr) {
                            portMappings.add(ContainerInfo.PortMapping.builder()
                                    .ip(binding.getHostIp())
                                    .privatePort(exposedPort.getPort())
                                    .publicPort(parsePort(binding.getHostPortSpec()))
                                    .type(exposedPort.getProtocol().name())
                                    .build());
                        }
                    }
                });
            }
        }

        String state = "unknown";
        String status = "";
        if (inspect.getState() != null) {
            state = inspect.getState().getStatus();
            Boolean running = inspect.getState().getRunning();
            if (running != null && running) {
                status = "Up since " + inspect.getState().getStartedAt();
            } else {
                status = "Exited (" + inspect.getState().getExitCodeLong() + ")";
            }
        }

        String networkMode = "";
        if (inspect.getHostConfig() != null && inspect.getHostConfig().getNetworkMode() != null) {
            networkMode = inspect.getHostConfig().getNetworkMode();
        }

        Long created = null;
        if (inspect.getCreated() != null) {
            try {
                created = java.time.Instant.parse(inspect.getCreated()).getEpochSecond();
            } catch (Exception ignored) {}
        }

        return ContainerInfo.builder()
                .id(inspect.getId().substring(0, 12))
                .fullId(inspect.getId())
                .name(name)
                .image(image)
                .status(status)
                .state(state)
                .ports(portMappings)
                .created(created)
                .networkMode(networkMode)
                .protectedContainer(isProtectedContainer(name))
                .build();
    }

    /**
     * 根据 Docker Statistics 计算资源使用情况
     *
     * docker-java 3.3.6 API 类型说明：
     *   - CpuStatsConfig.getSystemCpuUsage()      → Long (可 null)
     *   - CpuStatsConfig.getOnlineCpus()           → Long (可 null)
     *   - CpuUsageConfig.getTotalUsage()           → Long (可 null)
     *   - MemoryStatsConfig.getUsage()             → Long (可 null)
     *   - MemoryStatsConfig.getLimit()             → Long (可 null)
     *   - StatisticNetworksConfig.getRxBytes()     → Long (可 null)
     *   - StatisticNetworksConfig.getTxBytes()     → Long (可 null)
     *   - BlkioStatEntry.getValue()                → Long (可 null)
     *   - PidsStatsConfig.getCurrent()             → Long (可 null)
     *
     * 所有数值全部用 nullSafe() 包装后以 long 运算，杜绝 NPE 拆箱。
     * 不调用 memStats.getStats().get("cache") / get("inactive_file")，
     * 该 Map 在不同 Docker/cgroup 版本下 key 不稳定，直接用 usage 作为已用内存。
     */
    private ContainerStats calculateStats(String containerId, Statistics stats) {

        // ===== CPU 计算 =====
        // cpuPercent = (cpuDelta / systemDelta) * onlineCPUs * 100
        double cpuPercent = 0.0;
        try {
            CpuStatsConfig cpuStats = stats.getCpuStats();
            CpuStatsConfig preCpuStats = stats.getPreCpuStats();

            if (cpuStats != null && preCpuStats != null
                    && cpuStats.getCpuUsage() != null && preCpuStats.getCpuUsage() != null) {

                long currentTotal = safeL(cpuStats.getCpuUsage().getTotalUsage());
                long preTotal     = safeL(preCpuStats.getCpuUsage().getTotalUsage());

                // getSystemCpuUsage() 返回 Long，必须 null 检查
                Long sysCurr = cpuStats.getSystemCpuUsage();
                Long sysPre  = preCpuStats.getSystemCpuUsage();
                if (sysCurr == null || sysPre == null) {
                    // Windows 容器或 cgroup v2 可能无此值，跳过 CPU 计算
                    log.debug("systemCpuUsage 为 null，跳过 CPU 计算 [{}]", containerId);
                } else {
                    long cpuDelta    = currentTotal - preTotal;
                    long systemDelta = sysCurr - sysPre;

                    if (systemDelta > 0L && cpuDelta >= 0L) {
                        long onlineCpus = safeL(cpuStats.getOnlineCpus());
                        if (onlineCpus <= 0L) {
                            // 兜底：从 percpuUsage 数组长度推算
                            if (cpuStats.getCpuUsage().getPercpuUsage() != null) {
                                onlineCpus = cpuStats.getCpuUsage().getPercpuUsage().size();
                            }
                            if (onlineCpus <= 0L) {
                                onlineCpus = 1L;
                            }
                        }
                        cpuPercent = ((double) cpuDelta / (double) systemDelta) * onlineCpus * 100.0;
                        cpuPercent = Math.round(cpuPercent * 100.0) / 100.0;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CPU stats 计算异常 [{}]: {}", containerId, e.getMessage());
        }

        // ===== 内存计算 =====
        // 直接用 usage 作为已用内存，不读 memStats.getStats() map
        // memPercent = usage / limit * 100
        long memoryUsed  = 0L;
        long memoryLimit = 0L;
        double memoryPercent = 0.0;
        try {
            MemoryStatsConfig memStats = stats.getMemoryStats();
            if (memStats != null) {
                memoryUsed  = safeL(memStats.getUsage());
                memoryLimit = safeL(memStats.getLimit());

                // limit 为 0 或极大值（无限制时 Docker 返回 ~2^63）时不算百分比
                if (memoryLimit > 0L && memoryLimit < Long.MAX_VALUE / 2 && memoryUsed >= 0L) {
                    memoryPercent = ((double) memoryUsed / (double) memoryLimit) * 100.0;
                    memoryPercent = Math.round(memoryPercent * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.debug("Memory stats 计算异常 [{}]: {}", containerId, e.getMessage());
        }

        // ===== 网络 I/O =====
        long networkRx = 0L;
        long networkTx = 0L;
        try {
            Map<String, StatisticNetworksConfig> networks = stats.getNetworks();
            if (networks != null) {
                for (StatisticNetworksConfig netStat : networks.values()) {
                    if (netStat != null) {
                        networkRx += safeL(netStat.getRxBytes());
                        networkTx += safeL(netStat.getTxBytes());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Network stats 计算异常 [{}]: {}", containerId, e.getMessage());
        }

        // ===== 磁盘 I/O =====
        long blockRead  = 0L;
        long blockWrite = 0L;
        try {
            BlkioStatsConfig blkio = stats.getBlkioStats();
            if (blkio != null && blkio.getIoServiceBytesRecursive() != null) {
                for (BlkioStatEntry entry : blkio.getIoServiceBytesRecursive()) {
                    if (entry == null || entry.getOp() == null) continue;
                    if ("read".equalsIgnoreCase(entry.getOp())) {
                        blockRead += safeL(entry.getValue());
                    } else if ("write".equalsIgnoreCase(entry.getOp())) {
                        blockWrite += safeL(entry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Block I/O stats 计算异常 [{}]: {}", containerId, e.getMessage());
        }

        // ===== PIDs =====
        long pids = 0L;
        try {
            if (stats.getPidsStats() != null) {
                pids = safeL(stats.getPidsStats().getCurrent());
            }
        } catch (Exception ignored) {}

        // 容器名称
        String containerName = resolveContainerName(containerId);

        return ContainerStats.builder()
                .containerId(containerId)
                .containerName(containerName)
                .cpuPercent(cpuPercent)
                .memoryUsed(memoryUsed)
                .memoryLimit(memoryLimit)
                .memoryPercent(memoryPercent)
                .memoryUsedFormatted(formatBytes(memoryUsed))
                .memoryLimitFormatted(formatBytes(memoryLimit))
                .networkRx(networkRx)
                .networkTx(networkTx)
                .networkRxFormatted(formatBytes(networkRx))
                .networkTxFormatted(formatBytes(networkTx))
                .blockRead(blockRead)
                .blockWrite(blockWrite)
                .pids(pids)
                .build();
    }

    /**
     * Long → long 安全转换，null 返回 0L
     * docker-java 3.3.6 所有统计字段返回 Long 包装类型
     */
    private static long safeL(Long value) {
        return value != null ? value : 0L;
    }

    /**
     * 字节格式化为可读字符串
     */
    private String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 B";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private Integer parsePort(String portSpec) {
        if (portSpec == null || portSpec.isBlank()) return null;
        try {
            return Integer.parseInt(portSpec.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}