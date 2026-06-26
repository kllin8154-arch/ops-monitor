package com.opsmonitor.platform;

import com.opsmonitor.model.MonitorAgent;
import com.opsmonitor.service.AgentRegistryService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Agent 命令下发服务 (10D-3)
 *
 * Control Plane → Agent 的控制通道。
 * Agent 通过心跳拉取待执行命令（Pull 模式，不需要 Agent 暴露端口）。
 *
 * 支持命令：
 * - UPDATE_CONFIG：更新 Agent 采集配置
 * - DEPLOY_EXPORTER：部署新 Exporter
 * - REMOVE_EXPORTER：移除 Exporter
 * - UPDATE_LABELS：更新标签策略
 * - RESTART_COLLECTOR：重启采集器
 * - UPGRADE_AGENT：升级 Agent 版本
 *
 * FIX-CMD: 所有命令类型必须在 ALLOWED_COMMAND_TYPES 白名单内，防止任意命令注入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCommandService {

    private final AgentRegistryService agentRegistry;

    /** 待下发命令队列: agentId → Queue<AgentCommand> */
    private final Map<String, ConcurrentLinkedQueue<AgentCommand>> pendingCommands = new ConcurrentHashMap<>();

    /**
     * FIX-CMD: 允许下发的命令类型白名单（严格枚举，拒绝任何不在此列表中的类型）
     */
    private static final Set<String> ALLOWED_COMMAND_TYPES = Set.of(
            "UPDATE_CONFIG",      // 更新 Agent 采集配置
            "DEPLOY_EXPORTER",    // 部署新 Exporter
            "REMOVE_EXPORTER",    // 移除 Exporter
            "UPDATE_LABELS",      // 更新标签策略
            "RESTART_COLLECTOR",  // 重启采集器
            "UPGRADE_AGENT"       // 升级 Agent 版本
    );

    /**
     * 下发命令到指定 Agent
     *
     * FIX-CMD: 发送前校验命令类型合法性
     */
    public AgentCommand sendCommand(String agentId, AgentCommand command) {
        MonitorAgent agent = agentRegistry.getAgent(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent 不存在: " + agentId);
        }

        // FIX-CMD: 枚举校验命令类型，拒绝白名单外的任意命令
        if (command.getType() == null || !ALLOWED_COMMAND_TYPES.contains(command.getType())) {
            log.warn("[AgentCommand] 拒绝非法命令类型: type={} agentId={}", command.getType(), agentId);
            throw new IllegalArgumentException(
                    "非法命令类型: '" + command.getType() + "'。" +
                            "允许的类型: " + String.join(", ", ALLOWED_COMMAND_TYPES));
        }

        command.setCommandId(UUID.randomUUID().toString().substring(0, 8));
        command.setCreatedAt(System.currentTimeMillis());
        command.setStatus("PENDING");

        pendingCommands.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>())
                .offer(command);

        log.info("[AgentCommand] 已下发命令到 {}: {} ({})", agentId, command.getType(), command.getCommandId());
        return command;
    }

    /**
     * Agent 拉取待执行命令（心跳时调用）
     */
    public List<AgentCommand> pullCommands(String agentId) {
        ConcurrentLinkedQueue<AgentCommand> queue = pendingCommands.get(agentId);
        if (queue == null || queue.isEmpty()) return List.of();

        List<AgentCommand> commands = new ArrayList<>();
        AgentCommand cmd;
        while ((cmd = queue.poll()) != null) {
            cmd.setStatus("DELIVERED");
            commands.add(cmd);
        }
        return commands;
    }

    /**
     * 广播命令到所有在线 Agent
     *
     * FIX-CMD: 通过 sendCommand 路由，自动获得类型白名单校验
     */
    public int broadcast(AgentCommand template) {
        // FIX-CMD: 预先校验类型，让错误在广播前统一报出（而不是在循环中部分成功）
        if (template.getType() == null || !ALLOWED_COMMAND_TYPES.contains(template.getType())) {
            throw new IllegalArgumentException(
                    "非法广播命令类型: '" + template.getType() + "'。" +
                            "允许的类型: " + String.join(", ", ALLOWED_COMMAND_TYPES));
        }
        int sent = 0;
        for (MonitorAgent agent : agentRegistry.listAgents()) {
            if ("ONLINE".equals(agent.getStatus())) {
                AgentCommand cmd = AgentCommand.builder()
                        .type(template.getType())
                        .payload(template.getPayload())
                        .build();
                sendCommand(agent.getAgentId(), cmd);
                sent++;
            }
        }
        log.info("[AgentCommand] 广播命令 {} 到 {} 个 Agent", template.getType(), sent);
        return sent;
    }

    /**
     * 下发 Agent 采集配置
     */
    public AgentCommand sendConfig(String agentId, AgentConfigDTO config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scrapeInterval", config.getScrapeInterval());
        payload.put("enabledExporterTypes", config.getEnabledExporterTypes());
        payload.put("labelOverrides", config.getLabelOverrides());
        payload.put("remoteWriteEnabled", config.isRemoteWriteEnabled());

        return sendCommand(agentId, AgentCommand.builder()
                .type("UPDATE_CONFIG").payload(payload).build());
    }

    /**
     * 下发 Exporter 部署命令
     */
    public AgentCommand sendDeployExporter(String agentId, String exporterType,
                                           String targetAddress, int port) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exporterType", exporterType);
        payload.put("targetAddress", targetAddress);
        payload.put("port", port);

        return sendCommand(agentId, AgentCommand.builder()
                .type("DEPLOY_EXPORTER").payload(payload).build());
    }

    // ==================== 数据模型 ====================

    @Data
    @Builder
    public static class AgentCommand {
        private String commandId;
        private String type; // UPDATE_CONFIG / DEPLOY_EXPORTER / REMOVE_EXPORTER / UPDATE_LABELS / RESTART_COLLECTOR / UPGRADE_AGENT
        private Map<String, Object> payload;
        private String status; // PENDING / DELIVERED / EXECUTED / FAILED
        @Builder.Default
        private long createdAt = System.currentTimeMillis();
    }

    @Data
    @Builder
    public static class AgentConfigDTO {
        @Builder.Default
        private int scrapeInterval = 15;
        private List<String> enabledExporterTypes;
        private Map<String, String> labelOverrides;
        @Builder.Default
        private boolean remoteWriteEnabled = true;
    }
}