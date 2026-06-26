package com.opsmonitor.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知渠道管理服务 (11A)
 *
 * 职责：
 * - 渠道 CRUD（持久化到 data/notification-channels.json）
 * - 原子写入（.tmp → rename）
 * - 渠道查询（按 id / 按类型 / 全部）
 */
@Slf4j
@Service
public class NotificationChannelService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 内存索引 */
    private final Map<String, NotificationChannel> channels = new ConcurrentHashMap<>();

    // N24修复：注入 OpsMonitorProperties，数据路径基于 workDir 动态计算
    private final OpsMonitorProperties properties;

    public NotificationChannelService(OpsMonitorProperties properties) {
        this.properties = properties;
        loadFromDisk();
    }

    /**
     * N24修复：获取绝对数据文件路径，基于 workDir 而非进程工作目录
     */
    private java.nio.file.Path getDataFile() {
        return java.nio.file.Paths.get(
                properties.getCompose().getWorkDir(), "..", "data", "notification-channels.json"
        ).normalize();
    }

    // ==================== CRUD ====================

    public NotificationChannel create(NotificationChannel channel) {
        if (channel.getId() == null || channel.getId().isBlank()) {
            channel.setId("nc-" + System.currentTimeMillis());
        }
        channel.setCreatedAt(System.currentTimeMillis());
        channels.put(channel.getId(), channel);
        persist();
        log.info("[NotificationChannel] 创建渠道: {} ({})", channel.getName(), channel.getType());
        return channel;
    }

    public Optional<NotificationChannel> findById(String id) {
        return Optional.ofNullable(channels.get(id));
    }

    public List<NotificationChannel> listAll() {
        return new ArrayList<>(channels.values());
    }

    public List<NotificationChannel> listEnabled() {
        return channels.values().stream()
                .filter(NotificationChannel::isEnabled)
                .toList();
    }

    public boolean update(String id, NotificationChannel updated) {
        if (!channels.containsKey(id)) return false;
        updated.setId(id);
        updated.setCreatedAt(channels.get(id).getCreatedAt());
        channels.put(id, updated);
        persist();
        log.info("[NotificationChannel] 更新渠道: {}", id);
        return true;
    }

    public boolean delete(String id) {
        if (channels.remove(id) == null) return false;
        persist();
        log.info("[NotificationChannel] 删除渠道: {}", id);
        return true;
    }

    public boolean toggle(String id, boolean enabled) {
        NotificationChannel ch = channels.get(id);
        if (ch == null) return false;
        ch.setEnabled(enabled);
        persist();
        return true;
    }

    /** 更新统计（内存，不持久化） */
    public void recordSent(String id, boolean success) {
        NotificationChannel ch = channels.get(id);
        if (ch == null) return;
        if (success) ch.setSentCount(ch.getSentCount() + 1);
        else ch.setFailCount(ch.getFailCount() + 1);
        ch.setLastSentAt(System.currentTimeMillis());
    }

    // ==================== 持久化 ====================

    private void loadFromDisk() {
        Path path = getDataFile();
        if (!Files.exists(path)) return;
        try {
            List<NotificationChannel> list = MAPPER.readValue(path.toFile(),
                    new TypeReference<List<NotificationChannel>>() {});
            list.forEach(c -> channels.put(c.getId(), c));
            log.info("[NotificationChannel] 已加载 {} 个渠道", channels.size());
        } catch (IOException e) {
            log.warn("[NotificationChannel] 加载失败（忽略）: {}", e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Path target = getDataFile();
            Path dataDir = target.getParent();
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);

            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), new ArrayList<>(channels.values()));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("[NotificationChannel] 持久化失败: {}", e.getMessage());
        }
    }
}