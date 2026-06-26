package com.opsmonitor.service;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.ServerNode;
import com.opsmonitor.monitor.ExporterManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import com.opsmonitor.service.GrafanaSyncService;

/**
 * 服务器节点管理服务（8A-修正版）
 *
 * 安全加固：
 * - ReentrantLock 保护所有文件读写，防止并发损坏
 * - 删除服务器时级联清理关联 exporter + Prometheus target
 */
@Slf4j
@Service
public class ServerService {

    private final OpsMonitorProperties properties;
    private final ExporterManager exporterManager;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ServerNode> nodes = new ConcurrentHashMap<>();
    private final ReentrantLock fileLock = new ReentrantLock();

    /** BUG-7修复：使用有界线程池替代裸 new Thread()，防止线程数无限增长 */
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "server-async");
        t.setDaemon(true);
        return t;
    });

    /** 延迟注入，避免循环依赖；required=false 保证未注册时不报错 */
    @Lazy
    @Autowired(required = false)
    private ServerDashboardService dashboardService;

    /** Grafana 仪表盘同步服务（@Lazy 避免循环依赖） */
    @Lazy
    @Autowired(required = false)
    private GrafanaSyncService grafanaSyncService;

    public static final String LOCAL_SERVER_ID = "local";

    /**
     * @Lazy 打破循环依赖：ServerService ↔ ExporterManagerImpl
     */
    public ServerService(OpsMonitorProperties properties, @Lazy ExporterManager exporterManager) {
        this.properties = properties;
        this.exporterManager = exporterManager;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        fileLock.lock();
        try {
            loadFromFile();
            ensureLocalNode();
        } finally {
            fileLock.unlock();
        }
        log.info("服务器节点管理就绪，共 {} 个节点", nodes.size());
    }

    // ==================== CRUD ====================

    public ServerNode addServer(ServerNode node) {
        fileLock.lock();
        try {
            if (node.getId() == null || node.getId().isBlank()) {
                node.setId(generateId(node.getHost()));
            }
            if (nodes.containsKey(node.getId())) {
                throw new IllegalArgumentException("服务器 ID 已存在: " + node.getId());
            }

            // v2.11 P1-Dup: 重复 host 检测，防止同一台机器被注册两次产生重复 Incident
            String newHost = node.getHost() == null ? "" : node.getHost().trim().toLowerCase();
            // BUG-A 修复：检查是否与本机 local 节点 host 冲突（如重复添加 127.0.0.1）
            ServerNode localNode = nodes.get(LOCAL_SERVER_ID);
            if (localNode != null && !newHost.isEmpty()
                    && newHost.equals(localNode.getHost().trim().toLowerCase())) {
                throw new IllegalArgumentException(
                        "本机 (127.0.0.1) 已默认存在，请勿重复添加");
            }
            String localPublicIp = detectLocalPublicIp();
            for (ServerNode existing : nodes.values()) {
                if (LOCAL_SERVER_ID.equals(existing.getId())) continue; // 跳过内置 local 节点
                String existHost = existing.getHost() == null ? "" : existing.getHost().trim().toLowerCase();
                // 完全相同的 host/IP
                if (!newHost.isEmpty() && newHost.equals(existHost)) {
                    throw new IllegalArgumentException(
                            "已存在 host=" + node.getHost() + " 的服务器「" + existing.getName() + "」，请勿重复添加");
                }
                // 一个是 127.0.0.1/localhost，另一个是本机公网 IP — 实际指向同一台机器
                if (localPublicIp != null && isLocalAddress(newHost) && localPublicIp.equals(existHost)) {
                    throw new IllegalArgumentException(
                            "服务器「" + existing.getName() + "」的 IP " + existing.getHost()
                                    + " 是本机公网 IP，与新建的 127.0.0.1 实际指向同一台机器，请勿重复添加");
                }
                if (localPublicIp != null && isLocalAddress(existHost) && localPublicIp.equals(newHost)) {
                    throw new IllegalArgumentException(
                            "新建 host=" + node.getHost() + " 是本机公网 IP，"
                                    + "已有节点「" + existing.getName() + "」的 127.0.0.1 实际指向同一台机器，请勿重复添加");
                }
            }

            if (node.getCreatedAt() == 0) {
                node.setCreatedAt(System.currentTimeMillis());
            }
            nodes.put(node.getId(), node);
            saveToFile();
            log.info("新增服务器: {} ({})", node.getName(), node.getHost());
            // 异步触发：生成专属仪表盘 + 端口扫描（不影响注册流程）
            if (dashboardService != null) {
                final ServerNode finalNode = node;
                asyncExecutor.submit(() -> dashboardService.onServerAdded(finalNode));
            }
            // 通知 GrafanaSyncService 同步仪表盘（异步）
            if (grafanaSyncService != null) {
                final ServerNode syncNode = node;
                asyncExecutor.submit(() -> grafanaSyncService.onServerChanged(syncNode));
            }
            return node;
        } finally {
            fileLock.unlock();
        }
    }

    public ServerNode updateServer(String id, ServerNode update) {
        fileLock.lock();
        try {
            ServerNode existing = getServerInternal(id);
            if (update.getName() != null) existing.setName(update.getName());
            if (update.getHost() != null) existing.setHost(update.getHost());
            if (update.getDescription() != null) existing.setDescription(update.getDescription());
            if (update.getType() != null) existing.setType(update.getType());
            saveToFile();
            log.info("更新服务器: {} ({})", existing.getName(), existing.getHost());
            if (dashboardService != null) {
                final ServerNode finalNode = existing;
                asyncExecutor.submit(() -> dashboardService.onServerUpdated(finalNode));
            }
            // 通知 GrafanaSyncService 同步仪表盘（异步）
            if (grafanaSyncService != null) {
                final ServerNode syncNode = existing;
                asyncExecutor.submit(() -> grafanaSyncService.onServerChanged(syncNode));
            }
            return existing;
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * 删除服务器 + 级联清理关联 exporter
     */
    public void deleteServer(String id) {
        if (LOCAL_SERVER_ID.equals(id)) {
            throw new IllegalArgumentException("不能删除本机节点");
        }

        // 先级联删除关联 exporter（在锁外执行，因为 unregister 会操作 Docker/Prometheus）
        cascadeDeleteExporters(id);

        fileLock.lock();
        try {
            ServerNode removed = nodes.remove(id);
            if (removed == null) {
                throw new IllegalArgumentException("服务器不存在: " + id);
            }
            saveToFile();
            if (dashboardService != null) dashboardService.onServerDeleted(id);
            log.info("删除服务器: {} ({})，已级联清理关联 exporter", removed.getName(), removed.getHost());

            // 通知 GrafanaSyncService：重建全局仪表盘 + Grafana reload（异步）
            if (grafanaSyncService != null) {
                final String deletedId = id;
                asyncExecutor.submit(() -> grafanaSyncService.onServerDeleted(deletedId));
            }
        } finally {
            fileLock.unlock();
        }
    }

    public ServerNode getServer(String id) {
        ServerNode node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("服务器不存在: " + id);
        return node;
    }

    public List<ServerNode> listServers() {
        List<ServerNode> list = new ArrayList<>(nodes.values());
        list.sort((a, b) -> {
            if (LOCAL_SERVER_ID.equals(a.getId())) return -1;
            if (LOCAL_SERVER_ID.equals(b.getId())) return 1;
            return Long.compare(a.getCreatedAt(), b.getCreatedAt());
        });
        return list;
    }

    public boolean exists(String id) {
        return nodes.containsKey(id);
    }

    // ==================== 级联操作 ====================

    /**
     * 级联删除指定服务器下的所有 exporter
     */
    private void cascadeDeleteExporters(String serverId) {
        try {
            List<String> toDelete = exporterManager.listExporters().stream()
                    .filter(e -> serverId.equals(e.getServerId()))
                    .map(e -> e.getId())
                    .toList();

            if (toDelete.isEmpty()) return;

            log.info("级联删除服务器 {} 下的 {} 个 exporter", serverId, toDelete.size());
            for (String exporterId : toDelete) {
                try {
                    exporterManager.unregisterExporter(exporterId);
                } catch (Exception e) {
                    log.warn("级联删除 exporter {} 失败: {}", exporterId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("级联删除 exporter 异常: {}", e.getMessage());
        }
    }

    // ==================== 持久化 ====================

    private Path getDataFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "servers.json").normalize();
    }

    /** 调用前必须持有 fileLock */
    private void loadFromFile() {
        Path path = getDataFile();
        if (!Files.exists(path)) {
            log.info("servers.json 不存在，初始化空列表");
            return;
        }
        try {
            List<ServerNode> list = mapper.readValue(path.toFile(), new TypeReference<>() {});
            for (ServerNode node : list) {
                nodes.put(node.getId(), node);
            }
            log.info("从 {} 加载 {} 个服务器节点", path, list.size());
        } catch (IOException e) {
            log.error("加载 servers.json 失败: {}", e.getMessage());
        }
    }

    /** 调用前必须持有 fileLock */
    private void saveToFile() {
        Path path = getDataFile();
        Path tmp  = path.resolveSibling("servers.json.tmp");
        try {
            Files.createDirectories(path.getParent());
            mapper.writeValue(tmp.toFile(), new ArrayList<>(nodes.values()));
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存 servers.json 失败: {}", e.getMessage());
            try { java.nio.file.Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private void ensureLocalNode() {
        if (!nodes.containsKey(LOCAL_SERVER_ID)) {
            ServerNode local = ServerNode.builder()
                    .id(LOCAL_SERVER_ID)
                    .name("本机")
                    .host("127.0.0.1")
                    .type("LOCAL")
                    .description("本机（Docker 模式）")
                    .build();
            nodes.put(LOCAL_SERVER_ID, local);
            saveToFile();
        }
    }

    /** 内部使用，不抛异常版本 */
    private ServerNode getServerInternal(String id) {
        ServerNode node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("服务器不存在: " + id);
        return node;
    }

    private String generateId(String host) {
        String base = (host != null ? host.replaceAll("[^a-zA-Z0-9]", "-") : "srv");
        String id = base;
        int i = 1;
        while (nodes.containsKey(id)) {
            id = base + "-" + i++;
        }
        return id;
    }
    /**
     * v2.11 P1-Dup: 判断是否为回环/本机地址
     */
    private boolean isLocalAddress(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || h.startsWith("0.");
    }

    /**
     * v2.11 P1-Dup: 获取本机公网 IP（用于与 127.0.0.1 做等价检测）
     * 失败时返回 null，调用方需判空
     */
    private String detectLocalPublicIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.debug("[ServerService] detectLocalPublicIp 失败（可忽略）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * v2.10 P1-07 修复:注册线程池关闭钩子,避免 Spring DevTools 热重启/多次 @SpringBootTest 累积 → OOM
     */
    @PreDestroy
    public void shutdownThreadPool_v210() {
        try {
            if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
                asyncExecutor.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
}