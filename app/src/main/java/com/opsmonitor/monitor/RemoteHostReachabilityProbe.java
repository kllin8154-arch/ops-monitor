package com.opsmonitor.monitor;

import com.opsmonitor.model.ServerNode;
import com.opsmonitor.service.ServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 远程主机活性探测服务 (BUG-HOST-DOWN 修复)
 *
 * 问题背景：
 *   当远程主机关机后，nginx/postgres 等中间件 Exporter 容器跑在本机 Docker，
 *   Prometheus 抓取本机容器的 up 指标仍为 1，导致蜂窝图仍显示"在线"。
 *
 * 本类职责：
 *   - 每 30 秒对 servers.json 中所有 REMOTE 服务器做 TCP 活性探测
 *   - 维护内存 Map 供 ExporterHealthService 和前端查询
 *   - 不依赖 ICMP（需 root），改用 TCP connect 常用端口
 *
 * 遵循 AGENT_RULES：
 *   - 扁平包，不新增子包
 *   - 不引入新 Maven 依赖（仅用 JDK java.net.Socket）
 *   - 新增类，不重构已有模块
 */
@Slf4j
@Service
public class RemoteHostReachabilityProbe {

    private final ServerService serverService;

    /** BUG-HOST-DOWN 阶段 C：可配置探测策略（端口/超时/阈值） */
    private final ReachabilityConfig config;

    /**
     * BUG-HOST-DOWN 阶段 C（选项 B）：
     * 改用 Spring ApplicationEventPublisher 解耦事件分发，
     * 不再直接持有 ReachabilityIncidentBridge 引用，
     * 消除并发写文件冲突 + 支持多消费者订阅
     */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 探测结果缓存: serverId -> true(可达) / false(不可达) */
    private final Map<String, Boolean> reachability = new ConcurrentHashMap<>();

    /** 上次探测时间戳: serverId -> epoch ms */
    private final Map<String, Long> lastCheckTime = new ConcurrentHashMap<>();

    /** 连续失败计数（避免瞬时网络抖动误判） */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** 兜底默认值：未配置时使用（与阶段 A 行为一致） */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_FAILURE_THRESHOLD = 2;
    private static final int[] DEFAULT_PROBE_PORTS = {22, 3389, 80, 8080, 443};

    /** 并发探测线程池（daemon，4 核够用） */
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "host-probe");
        t.setDaemon(true);
        return t;
    });

    public RemoteHostReachabilityProbe(@Lazy ServerService serverService,
                                       ReachabilityConfig config,
                                       org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.serverService = serverService;
        this.config = config;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 每 30 秒执行一次全量探测（应用启动 30s 后开始，给启动留余量）
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void probeAll() {
        try {
            // 阶段 C：全局开关
            if (config != null && !config.isEnabled()) {
                return;
            }
            List<ServerNode> servers = serverService.listServers();
            if (servers.isEmpty()) return;

            int threshold = config != null
                    ? config.getFailureThreshold() : DEFAULT_FAILURE_THRESHOLD;

            int reachable = 0, unreachable = 0, skipped = 0;
            for (ServerNode node : servers) {
                // FIX-PROBE-1: 跳过所有本机地址，防止把宿主机判为"不可达"
                // 包括：id=local、type=LOCAL、host=127.0.0.1、host=localhost、host=本机真实IP
                if ("local".equals(node.getId())
                        || "LOCAL".equals(node.getType())
                        || isLocalMachineHost(node.getHost())) {
                    reachability.put(node.getId(), true);
                    lastCheckTime.put(node.getId(), System.currentTimeMillis());
                    consecutiveFailures.put(node.getId(), 0);
                    skipped++;
                    log.debug("[Probe] 跳过本机地址探测: id={}, host={}", node.getId(), node.getHost());
                    continue;
                }
                if (node.getHost() == null || node.getHost().isBlank()) {
                    skipped++;
                    continue;
                }

                // 阶段 C：保留旧状态用于翻转检测
                Boolean previous = reachability.get(node.getId());

                boolean ok = probeOne(node.getHost());
                int fails = ok ? 0 : consecutiveFailures.getOrDefault(node.getId(), 0) + 1;
                consecutiveFailures.put(node.getId(), fails);

                Boolean newState = null;
                if (ok) {
                    reachability.put(node.getId(), true);
                    newState = true;
                    reachable++;
                } else if (fails >= threshold) {
                    reachability.put(node.getId(), false);
                    newState = false;
                    unreachable++;
                } else {
                    // 未达阈值，保留旧值（若无旧值默认可达以避免首次启动误杀）
                    reachability.putIfAbsent(node.getId(), true);
                }
                lastCheckTime.put(node.getId(), System.currentTimeMillis());

                // 阶段 C（选项 B）：状态翻转事件 → 发布 Spring Event
                if (newState != null && !Objects.equals(previous, newState)) {
                    publishStateChangeEvent(node, previous, newState);
                }
            }

            // 清理已删除服务器的残留状态
            Set<String> activeIds = new HashSet<>();
            for (ServerNode n : servers) activeIds.add(n.getId());
            reachability.keySet().removeIf(k -> !activeIds.contains(k));
            lastCheckTime.keySet().removeIf(k -> !activeIds.contains(k));
            consecutiveFailures.keySet().removeIf(k -> !activeIds.contains(k));

            log.debug("主机活性探测：可达={}, 不可达={}, 跳过={}", reachable, unreachable, skipped);
        } catch (Exception e) {
            log.warn("主机活性探测异常：{}", e.getMessage());
        }
    }

    /**
     * 阶段 C（选项 B）：状态翻转事件发布
     *
     * 通过 Spring ApplicationEventPublisher 广播事件，
     * 由 ReachabilityIncidentBridge（@EventListener）异步消费。
     * 不再直接持有任何业务模块引用，彻底解耦。
     */
    private void publishStateChangeEvent(ServerNode node, Boolean previous, Boolean current) {
        try {
            String reason;
            if (Boolean.FALSE.equals(current)) {
                reason = String.format(
                        "TCP 探测失败 host=%s, 探测端口=%s, 连续失败 %d 次",
                        node.getHost(),
                        config != null ? config.getProbePorts() : Arrays.toString(DEFAULT_PROBE_PORTS),
                        config != null ? config.getFailureThreshold() : DEFAULT_FAILURE_THRESHOLD);
                log.warn("主机状态翻转 [可达 → 不可达]: serverId={}, host={}",
                        node.getId(), node.getHost());
            } else {
                reason = "主机 TCP 探测恢复成功";
                log.info("主机状态翻转 [不可达 → 可达]: serverId={}, host={}",
                        node.getId(), node.getHost());
            }

            HostReachabilityChangedEvent event = new HostReachabilityChangedEvent(
                    this,
                    HostReachabilityChangedEvent.SourceType.HOST,
                    node.getId(),
                    node.getName(),
                    Boolean.TRUE.equals(current),
                    reason,
                    node.getHost());  // v2.11 FIX-BRIDGE-2: 传入 host IP
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.debug("状态翻转事件发布异常: {}", e.getMessage());
        }
    }

    /**
     * 对单个 host 进行 TCP connect 探测，任一常用端口成功即认为可达
     * 阶段 C：从 ReachabilityConfig 读取端口列表与超时
     */
    private boolean probeOne(String host) {
        int timeout = config != null
                ? config.getConnectTimeoutMs() : DEFAULT_CONNECT_TIMEOUT_MS;
        List<Integer> ports = (config != null && config.getProbePorts() != null
                && !config.getProbePorts().isEmpty())
                ? config.getProbePorts() : null;

        if (ports == null) {
            for (int port : DEFAULT_PROBE_PORTS) {
                if (tcpReachable(host, port, timeout)) return true;
            }
        } else {
            for (Integer port : ports) {
                if (port == null) continue;
                if (tcpReachable(host, port, timeout)) return true;
            }
        }
        return false;
    }

    /**
     * TCP connect 活性检测
     *
     * 关键语义：
     *   - connect 成功 => 主机可达（即使应用拒绝也说明 OS 在线）
     *   - ConnectException(拒绝连接) => 主机可达但端口关闭，返回 true
     *   - SocketTimeoutException => 主机不可达（关机/网络不通）
     *   - 其他异常 => 视为不可达
     */
    private boolean tcpReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (java.net.ConnectException ce) {
            // 连接被拒绝 => 主机在线（操作系统回了 RST），只是端口不监听
            String msg = ce.getMessage() == null ? "" : ce.getMessage().toLowerCase();
            if (msg.contains("refused")) {
                return true;
            }
            return false;
        } catch (java.net.SocketTimeoutException ste) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 对外查询接口 ====================

    /**
     * 查询某台服务器是否可达
     *
     * @param serverId 服务器 ID
     * @return true=可达或未探测过（保守默认）, false=已判定不可达
     */
    public boolean isReachable(String serverId) {
        if (serverId == null) return true;
        // 未探测过的服务器默认返回 true，避免启动初期误判
        return reachability.getOrDefault(serverId, Boolean.TRUE);
    }

    /**
     * 获取所有服务器的可达性快照（只读拷贝）
     */
    public Map<String, Boolean> getAllReachability() {
        return Map.copyOf(reachability);
    }

    /**
     * 获取上次探测时间
     */
    public long getLastCheckTime(String serverId) {
        return lastCheckTime.getOrDefault(serverId, 0L);
    }

    /**
     * 手动触发探测（供测试 / 故障排查）
     */
    public void probeNow() {
        probeExecutor.submit(this::probeAll);
    }

    /**
     * FIX-PROBE-1: 判断是否为本机地址，防止将本机探测为不可达
     * 判断规则：127.0.0.1 / localhost / ::1 / 本机真实 IP
     */
    private static volatile String LOCAL_MACHINE_IP = null;

    private boolean isLocalMachineHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        if ("127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)) return true;
        // 懒加载本机 IP，失败时不影响探测
        if (LOCAL_MACHINE_IP == null) {
            try {
                LOCAL_MACHINE_IP = java.net.InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                LOCAL_MACHINE_IP = "";
            }
        }
        return !LOCAL_MACHINE_IP.isEmpty() && LOCAL_MACHINE_IP.equals(host.trim());
    }

    /**
     * 应用关闭时清理线程池
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        probeExecutor.shutdown();
        try {
            if (!probeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                probeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}