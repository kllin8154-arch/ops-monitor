package com.opsmonitor.service;

import com.opsmonitor.model.ServerNode;
import com.opsmonitor.monitor.ExporterManager;
import com.opsmonitor.monitor.HostReachabilityChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Agent 离线 → Exporter 级联状态标记 (v2.13-A)
 *
 * 监听 Agent 心跳超时事件（HostReachabilityChangedEvent, sourceType=AGENT）：
 *   - Agent OFFLINE → 将该 Agent 所在服务器的所有 Exporter 标记为 AGENT_OFFLINE
 *   - Agent 恢复 ONLINE → 清除 Exporter 的 agentStatus（设为 null）
 *
 * 关联方式：Agent IP → ServerNode.host → ExporterInstance.serverId
 * 不修改 AgentRegistryService，不删除 Exporter，仅做状态标记。
 */
@Slf4j
@Component
public class AgentExporterCascade {

    private final ExporterManager exporterManager;
    private final ServerService serverService;

    public AgentExporterCascade(ExporterManager exporterManager, ServerService serverService) {
        this.exporterManager = exporterManager;
        this.serverService = serverService;
    }

    @EventListener
    public void onHostReachabilityChanged(HostReachabilityChangedEvent event) {
        if (event.getSourceType() != HostReachabilityChangedEvent.SourceType.AGENT) {
            return; // 只处理 Agent 事件
        }

        String agentIp = event.getHostIp();
        if (agentIp == null || agentIp.isBlank()) {
            log.debug("Agent 事件缺少 hostIp，跳过级联: agentId={}", event.getServerId());
            return;
        }

        // 通过 Agent IP 查找匹配的服务器节点
        ServerNode matchedServer = findServerByIp(agentIp);
        if (matchedServer == null) {
            log.debug("未找到匹配 Agent IP [{}] 的服务器节点，跳过级联", agentIp);
            return;
        }

        if (event.isReachable()) {
            // Agent 恢复 ONLINE → 清除 Exporter 的 agentStatus
            exporterManager.cascadeAgentStatus(matchedServer.getId(), null);
            log.info("Agent 恢复 ONLINE [{}]，已清除关联 Exporter 的 agentStatus: serverId={}",
                    agentIp, matchedServer.getId());
        } else {
            // Agent OFFLINE → 标记 Exporter 为 AGENT_OFFLINE
            exporterManager.cascadeAgentStatus(matchedServer.getId(), "AGENT_OFFLINE");
            log.warn("Agent OFFLINE [{}]，已级联标记 Exporter agentStatus=AGENT_OFFLINE: serverId={}",
                    agentIp, matchedServer.getId());
        }
    }

    /**
     * 根据 IP 地址查找对应的服务器节点
     * 大小写不敏感匹配
     */
    private ServerNode findServerByIp(String ip) {
        if (ip == null) return null;
        String targetIp = ip.trim().toLowerCase();
        for (ServerNode node : serverService.listServers()) {
            String host = node.getHost();
            if (host != null && host.trim().toLowerCase().equals(targetIp)) {
                return node;
            }
        }
        return null;
    }
}
