package com.opsmonitor.monitor;

import com.opsmonitor.sentinel.IncidentService;
import com.opsmonitor.sentinel.RunbookStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主机不可达 → Sentinel Incident 桥接器 (v2.11 重写)
 *
 * v2.11 重大修复：
 *   旧版直接读写 incidents.json 文件，与 IncidentService 内存 + 文件产生双写竞态：
 *     Bridge 写文件 → IncidentService 内存未感知 → 下次 save() 覆盖 Bridge 数据
 *   结果：前端查询走 IncidentService 内存，Bridge 写的 Incident 永远不显示。
 *
 * 修复方案（FIX-BRIDGE-1）：
 *   Bridge 完全放弃文件直接读写，改为调用 IncidentService.open() / resolveByFingerprint()。
 *   IncidentService 是唯一写入权威（单一数据源）。
 *
 * FIX-BRIDGE-2: rootCause 补充 host IP（原来只有 serverId/serverName）
 * FIX-BRIDGE-3: 本机地址事件兜底过滤（主探针已过滤，此处再次确认）
 */
@Slf4j
@Service
public class ReachabilityIncidentBridge {

    private final ReachabilityConfig reachConfig;

    /** FIX-BRIDGE-1: 注入 IncidentService 作为唯一写入权威 */
    @Lazy
    @Autowired(required = false)
    private IncidentService incidentService;

    /** 单线程串行队列：所有事件按发生顺序处理 */
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reach-incident-bridge");
        t.setDaemon(true);
        return t;
    });

    public ReachabilityIncidentBridge(ReachabilityConfig reachConfig) {
        this.reachConfig = reachConfig;
    }

    @EventListener
    public void onReachabilityChanged(HostReachabilityChangedEvent event) {
        if (!reachConfig.isIncidentOnDown()) return;
        if ("local".equals(event.getServerId())) return; // FIX-BRIDGE-3
        eventExecutor.submit(() -> handleEvent(event));
    }

    private void handleEvent(HostReachabilityChangedEvent event) {
        try {
            if (event.isReachable()) {
                onHostRecovered(event.getServerId());
            } else {
                String hostIp = event.getHostIp();
                onHostUnreachable(event.getServerId(), event.getServerName(),
                        event.getReason(), hostIp);
            }
        } catch (Exception e) {
            log.error("[IncidentBridge] 处理事件异常: {}", e.getMessage());
        }
    }

    public boolean onHostUnreachable(String serverId, String serverName,
                                     String reason, String hostIp) {
        if (!reachConfig.isIncidentOnDown()) return false;
        if (serverId == null || serverId.isBlank()) return false;

        if (incidentService == null) {
            log.warn("[IncidentBridge] IncidentService 未就绪，跳过 Incident 创建: {}", serverId);
            return false;
        }

        try {
            String ipInfo = (hostIp != null && !hostIp.isBlank()) ? hostIp : "未知IP";
            // FIX-BRIDGE-2: rootCause 包含 host IP，便于运维快速定位
            String detailedRootCause = String.format(
                    "TCP 探测失败 host=%s, 探测端口=%s, 连续失败 %d 次",
                    ipInfo,
                    reachConfig.getProbePorts(),
                    reachConfig.getFailureThreshold());
            if (reason != null && !reason.isBlank() && !reason.equals(detailedRootCause)) {
                detailedRootCause = reason;
            }

            List<RunbookStep> steps = buildRunbookSteps(serverId, serverName, ipInfo);
            incidentService.open(serverId,
                    serverName != null ? serverName : serverId,
                    "HOST_UNREACHABLE", "主机不可达",
                    reachConfig.getIncidentSeverity(),
                    100.0, 100.0, detailedRootCause, steps);
            log.warn("[IncidentBridge] Incident 已创建/复用: serverId={} host={}", serverId, ipInfo);
            return true;
        } catch (Exception e) {
            log.error("[IncidentBridge] 创建 Incident 失败: {}", e.getMessage());
            return false;
        }
    }

    /** 向后兼容旧调用 */
    public boolean onHostUnreachable(String serverId, String serverName, String reason) {
        return onHostUnreachable(serverId, serverName, reason, null);
    }

    public boolean onHostRecovered(String serverId) {
        if (!reachConfig.isIncidentOnDown()) return false;
        if (serverId == null || serverId.isBlank()) return false;
        if (incidentService == null) return false;

        try {
            boolean resolved = incidentService.resolveByFingerprint(
                    serverId, "HOST_UNREACHABLE", "主机已恢复可达，系统自动 RESOLVED");
            if (resolved) log.info("[IncidentBridge] 自动 RESOLVED: serverId={}", serverId);
            return resolved;
        } catch (Exception e) {
            log.error("[IncidentBridge] 自动恢复失败: {}", e.getMessage());
            return false;
        }
    }

    private List<RunbookStep> buildRunbookSteps(String serverId, String serverName, String hostIp) {
        List<RunbookStep> steps = new ArrayList<>();
        steps.add(RunbookStep.builder().name("诊断记录").type("LOG")
                .command(String.format("故障: 主机不可达 | serverId: %s | serverName: %s | host: %s",
                        serverId, serverName != null ? serverName : "N/A", hostIp))
                .failFast(false).build());
        steps.add(RunbookStep.builder().name("确认主机电源/网络状态").type("LOG")
                .command("请人工确认服务器电源、网线、交换机端口、防火墙规则")
                .failFast(false).build());
        steps.add(RunbookStep.builder().name("等待自动恢复检测").type("LOG")
                .command("主机恢复后 RemoteHostReachabilityProbe 将自动 RESOLVED 此 Incident")
                .failFast(false).build());
        return steps;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        eventExecutor.shutdown();
        try {
            if (!eventExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
                eventExecutor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}