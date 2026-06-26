package com.opsmonitor.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一显示名称服务 (10E-1)
 *
 * 管理所有资源的 displayName：
 * - Service / Exporter / Project / Agent 都可以设置用户可读的显示名
 * - displayName 优先显示，fallback 到技术 name
 * - 修改后联动：ResourceReconciler → Dashboard / Prometheus labels
 *
 * 持久化：data/display-names.json
 */
@Slf4j
@Service
public class DisplayNameService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;

    /** resourceKey → displayName */
    private final Map<String, String> displayNames = new ConcurrentHashMap<>();

    public DisplayNameService(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        loadFromJson();
        log.info("[DisplayName] 显示名称服务就绪，共 {} 条", displayNames.size());
    }

    /**
     * 设置 displayName
     * @param kind 资源类型 (Service/Exporter/Project/Agent)
     * @param name 技术名称（不可变）
     * @param displayName 用户可读名称
     */
    public void setDisplayName(String kind, String name, String displayName) {
        String key = kind + ":" + name;
        if (displayName == null || displayName.isBlank()) {
            displayNames.remove(key);
        } else {
            displayNames.put(key, displayName.trim());
        }
        saveToJson();
        log.info("[DisplayName] {} → {}", key, displayName);
    }

    /**
     * 获取 displayName（优先返回自定义名，fallback 到技术名）
     */
    public String getDisplayName(String kind, String name) {
        String key = kind + ":" + name;
        return displayNames.getOrDefault(key, name);
    }

    /**
     * 获取所有 displayName 映射
     */
    public Map<String, String> getAll() {
        return Map.copyOf(displayNames);
    }

    // ==================== 持久化 ====================

    private Path getFilePath() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "display-names.json").normalize();
    }

    @SuppressWarnings("unchecked")
    private void loadFromJson() {
        Path path = getFilePath();
        if (!Files.exists(path)) return;
        try {
            Map<String, String> loaded = mapper.readValue(path.toFile(), Map.class);
            displayNames.putAll(loaded);
        } catch (IOException e) {
            log.error("[DisplayName] 加载失败: {}", e.getMessage());
        }
    }

    private void saveToJson() {
        try {
            Path path = getFilePath();
            Path tmp  = path.resolveSibling("display-names.json.tmp");
            Files.createDirectories(path.getParent());
            mapper.writeValue(tmp.toFile(), displayNames);
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[DisplayName] 保存失败: {}", e.getMessage());
        }
    }
}