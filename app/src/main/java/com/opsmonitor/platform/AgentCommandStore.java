package com.opsmonitor.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 命令持久化存储 (10D.1 Hotfix-2)
 *
 * 存储：data/commands/{agentId}.json
 */
@Slf4j
@Service
public class AgentCommandStore {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;

    public AgentCommandStore(OpsMonitorProperties properties) {
        this.properties = properties;
        // 修复: 不使用 findAndRegisterModules()，JDK 17 缺少 javax.xml.bind
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void saveCommands(String agentId, List<AgentCommandService.AgentCommand> commands) {
        try {
            Path filePath = getCommandFile(agentId);
            Path tmp      = filePath.resolveSibling(agentId + "-commands.tmp");
            Files.createDirectories(filePath.getParent());
            mapper.writeValue(tmp.toFile(), commands);
            try {
                Files.move(tmp, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[CommandStore] 保存失败 {}: {}", agentId, e.getMessage());
        }
    }

    public List<AgentCommandService.AgentCommand> loadCommands(String agentId) {
        Path filePath = getCommandFile(agentId);
        if (!Files.exists(filePath)) return new ArrayList<>();
        try {
            return mapper.readValue(filePath.toFile(),
                    mapper.getTypeFactory().constructCollectionType(
                            List.class, AgentCommandService.AgentCommand.class));
        } catch (IOException e) {
            log.error("[CommandStore] 加载失败 {}: {}", agentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void clearCommands(String agentId) {
        try {
            Files.deleteIfExists(getCommandFile(agentId));
        } catch (IOException e) {
            log.debug("[CommandStore] 清理失败 {}: {}", agentId, e.getMessage());
        }
    }

    public List<String> listAgentIdsWithPendingCommands() {
        List<String> agentIds = new ArrayList<>();
        Path dir = getCommandDir();
        if (!Files.isDirectory(dir)) return agentIds;
        try (var files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".json"))
                    .forEach(f -> agentIds.add(f.getFileName().toString().replace(".json", "")));
        } catch (IOException ignored) {}
        return agentIds;
    }

    private Path getCommandFile(String agentId) {
        return getCommandDir().resolve(agentId + ".json");
    }

    private Path getCommandDir() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "commands").normalize();
    }
}