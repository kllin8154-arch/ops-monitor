package com.opsmonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.MonitorAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册中心 (10A-1)
 *
 * 管理远程 Agent 节点的注册、心跳、状态。
 * 持久化到 data/agents.json。
 *
 * Agent 生命周期：
 *   POST /api/agents/register → 注册
 *   POST /api/agents/{id}/heartbeat → 心跳续约
 *   60s 无心跳 → OFFLINE
 *
 * v2.11 FIX-AGENT-1: 修复"Agent 心跳超时 N 秒"Incident 每次重启都重新创建的根因
 *   根因：checkHeartbeats 将 agent 标记为 OFFLINE 后没有调用 saveToJson()
 *         → agents.json 里 status 永远是 ONLINE
 *         → 每次重启从文件加载时又是 ONLINE → 60秒后再次触发超时事件 → 重新创建 Incident
 *   修复：ONLINE→OFFLINE 翻转后立即持久化；已是 OFFLINE 的不重复发布事件
 */
@Slf4j
@Service
public class AgentRegistryService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper jsonMapper;

    /** Agent 内存缓存: agentId → MonitorAgent */
    private final Map<String, MonitorAgent> agents = new ConcurrentHashMap<>();

    /** 心跳超时（毫秒） */
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000;

    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public AgentRegistryService(OpsMonitorProperties properties,
                                org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void init() {
        loadFromJson();
        log.info("Agent 注册中心就绪，共 {} 个 Agent", agents.size());
    }

    /**
     * 注册 Agent
     */
    public MonitorAgent register(MonitorAgent agent) {
        if (agent.getAgentId() == null || agent.getAgentId().isBlank()) {
            agent.setAgentId(agent.getHostname() + "-" + agent.getIp());
        }
        agent.setStatus("ONLINE");
        agent.setRegisteredAt(System.currentTimeMillis());
        agent.setLastHeartbeat(System.currentTimeMillis());

        agents.put(agent.getAgentId(), agent);
        saveToJson();
        log.info("Agent 已注册: {} ({})", agent.getAgentId(), agent.getIp());
        return agent;
    }

    /**
     * 心跳续约
     */
    public boolean heartbeat(String agentId) {
        MonitorAgent agent = agents.get(agentId);
        if (agent == null) return false;
        boolean wasOffline = "OFFLINE".equals(agent.getStatus());
        agent.setLastHeartbeat(System.currentTimeMillis());
        agent.setStatus("ONLINE");

        if (wasOffline) {
            saveToJson(); // FIX-AGENT-1: 恢复 ONLINE 也持久化
            try {
                eventPublisher.publishEvent(
                        new com.opsmonitor.monitor.HostReachabilityChangedEvent(
                                this,
                                com.opsmonitor.monitor.HostReachabilityChangedEvent.SourceType.AGENT,
                                agent.getAgentId(),
                                agent.getHostname() != null ? agent.getHostname() : agent.getAgentId(),
                                true,
                                "Agent 心跳恢复",
                                agent.getIp()));
                log.info("Agent 恢复 ONLINE，已发布事件: {}", agent.getAgentId());
            } catch (Exception e) {
                log.debug("Agent 恢复事件发布异常: {}", e.getMessage());
            }
        }
        return true;
    }

    /**
     * 注销 Agent
     */
    public boolean unregister(String agentId) {
        MonitorAgent removed = agents.remove(agentId);
        if (removed != null) {
            saveToJson();
            log.info("Agent 已注销: {}", agentId);
            return true;
        }
        return false;
    }

    /**
     * 获取所有 Agent
     */
    public List<MonitorAgent> listAgents() {
        return new ArrayList<>(agents.values());
    }

    /**
     * 获取指定 Agent
     */
    public MonitorAgent getAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * 每 30 秒检查心跳超时
     *
     * v2.11 FIX-AGENT-1: 关键修复
     *
     * 旧版问题：
     *   1. 仅检查 status=="ONLINE" → 修改内存为 OFFLINE → 未持久化
     *   2. 重启后从文件加载：status 仍是 ONLINE（因为没存）
     *   3. 60s 后 checkHeartbeats 再次触发 → 又发布一次离线事件 → 再建一条 Incident
     *   4. 结果：每次重启都产生新的"心跳超时 N 秒"Incident，N 越来越大
     *
     * 修复：
     *   - ONLINE → OFFLINE 翻转后立即 saveToJson()（状态持久化）
     *   - 已是 OFFLINE 的 agent 不重复发布事件（静默跳过）
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void checkHeartbeats() {
        long now = System.currentTimeMillis();
        boolean anyChanged = false;

        for (MonitorAgent agent : agents.values()) {
            boolean timedOut = (now - agent.getLastHeartbeat()) > HEARTBEAT_TIMEOUT_MS;

            if ("ONLINE".equals(agent.getStatus()) && timedOut) {
                // 首次翻转 ONLINE → OFFLINE：持久化 + 发布事件
                agent.setStatus("OFFLINE");
                anyChanged = true;
                log.warn("Agent 心跳超时，标记 OFFLINE: {} ({})", agent.getAgentId(), agent.getIp());

                try {
                    long offlineSec = (now - agent.getLastHeartbeat()) / 1000L;
                    String reason = String.format(
                            "Agent 心跳超时 %d 秒（IP: %s, 主机名: %s）",
                            offlineSec, agent.getIp(), agent.getHostname());
                    eventPublisher.publishEvent(
                            new com.opsmonitor.monitor.HostReachabilityChangedEvent(
                                    this,
                                    com.opsmonitor.monitor.HostReachabilityChangedEvent.SourceType.AGENT,
                                    agent.getAgentId(),
                                    agent.getHostname() != null ? agent.getHostname() : agent.getAgentId(),
                                    false,
                                    reason,
                                    agent.getIp()));
                } catch (Exception e) {
                    log.debug("Agent OFFLINE 事件发布异常: {}", e.getMessage());
                }

            } else if ("OFFLINE".equals(agent.getStatus()) && timedOut) {
                // FIX-AGENT-1: 已是 OFFLINE 且仍超时 → 静默，不重复发布事件、不重复创建 Incident
                log.debug("Agent 持续 OFFLINE（超时 {}s），跳过重复事件: {}",
                        (now - agent.getLastHeartbeat()) / 1000L, agent.getAgentId());
            }
        }

        // FIX-AGENT-1: 只有发生 ONLINE→OFFLINE 翻转才持久化，避免无意义写文件
        if (anyChanged) {
            saveToJson();
        }
    }

    // ==================== 持久化 ====================

    private Path getAgentsFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "agents.json").normalize();
    }

    private void saveToJson() {
        try {
            Path path = getAgentsFile();
            Path tmp  = path.resolveSibling("agents.json.tmp");
            Files.createDirectories(path.getParent());
            jsonMapper.writeValue(tmp.toFile(), new ArrayList<>(agents.values()));
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存 agents.json 失败: {}", e.getMessage());
        }
    }

    private void loadFromJson() {
        Path path = getAgentsFile();
        if (!Files.exists(path)) return;
        try {
            List<MonitorAgent> list = jsonMapper.readValue(path.toFile(),
                    jsonMapper.getTypeFactory().constructCollectionType(List.class, MonitorAgent.class));
            for (MonitorAgent agent : list) {
                agents.put(agent.getAgentId(), agent);
            }
        } catch (IOException e) {
            log.error("加载 agents.json 失败: {}", e.getMessage());
        }
    }
}