package com.opsmonitor.monitor;

import jakarta.annotation.PreDestroy;
import com.opsmonitor.model.ServerNode;
import com.opsmonitor.service.ServerService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Exporter 自动发现服务 (9G-4)
 *
 * 扫描已注册服务器的常用端口，推断运行的服务类型。
 *
 * FIX-RATELIMIT: 添加每台服务器的扫描速率限制（冷却时间 60 秒）
 * 防止通过手动触发 POST /api/discovery/scan 对所有服务器发起大量 TCP 连接造成网络风暴。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExporterDiscoveryService {

    private final ServerService serverService;

    /** 端口 → 服务类型映射 */
    /**
     * 端口 → 服务类型映射（仅作为"疑似"推断的初始候选）
     *
     * v2.12 改进：
     *   旧逻辑：端口开放 = 服务确认（误判率高）
     *   新逻辑：端口开放 = 疑似候选 → 协议探针验证 → 确认/排除
     *
     * 用户自定义端口场景（如 MySQL 用 3307）：
     *   通过 Exporter 注册时的 targetAddress 参数显式指定，不依赖端口扫描。
     *   端口扫描仅用于"自动发现建议"，不用于最终判定。
     */
    private static final Map<Integer, String> PORT_SERVICE_MAP = new LinkedHashMap<>() {{
        put(80, "nginx");
        put(443, "nginx");
        put(6379, "redis");
        put(3306, "mysql");
        put(5432, "postgres");
        put(1521, "oracle");
        put(5236, "dm");
        put(54321, "kingbase");
        put(8080, "geoserver");
        put(9092, "jmx");
    }};

    /** TCP 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 2000;

    /**
     * FIX-RATELIMIT: 每台服务器的最小扫描间隔（毫秒）
     * 防止短时间内对同一服务器发起大量 TCP 连接
     */
    private static final long SCAN_COOLDOWN_MS = 60_000L; // 60 秒冷却

    /**
     * FIX-RATELIMIT: 记录每台服务器上次扫描时间（serverId → lastScanMs）
     */
    private final ConcurrentHashMap<String, Long> lastScanTime = new ConcurrentHashMap<>();

    /** 并行扫描线程池（限制并发数，防止线程爆炸） */
    private final ExecutorService scanPool = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "discovery-scan");
        t.setDaemon(true);
        return t;
    });

    /**
     * 扫描所有远程服务器，返回发现的服务
     *
     * FIX-RATELIMIT: 全量扫描时对每台服务器检查冷却时间，避免频繁调用造成网络冲击
     */
    public List<DiscoveredService> scanAllServers() {
        List<ServerNode> servers = serverService.listServers();
        List<DiscoveredService> allDiscovered = new ArrayList<>();
        int skipped = 0;

        for (ServerNode server : servers) {
            if ("local".equals(server.getId())) continue;
            if (server.getHost() == null || server.getHost().isBlank()) continue;

            // FIX-RATELIMIT: 检查冷却时间，未到冷却时间的服务器跳过
            if (isInCooldown(server.getId())) {
                skipped++;
                log.debug("[Discovery] 服务器 {} 在冷却中，跳过本次扫描（剩余冷却: {}s）",
                        server.getName(), remainingCooldownSeconds(server.getId()));
                continue;
            }

            try {
                List<DiscoveredService> discovered = scanServer(server);
                allDiscovered.addAll(discovered);
            } catch (Exception e) {
                log.warn("[Discovery] 扫描服务器 {} ({}) 失败: {}", server.getName(), server.getHost(), e.getMessage());
            }
        }

        log.info("[Discovery] 自动发现完成: 扫描 {} 台服务器（跳过冷却中 {} 台），发现 {} 个服务",
                servers.size() - 1 - skipped, skipped, allDiscovered.size());
        return allDiscovered;
    }

    /**
     * 扫描单台服务器
     *
     * FIX-RATELIMIT: 记录扫描时间，下次扫描前检查冷却
     */
    public List<DiscoveredService> scanServer(ServerNode server) {
        String host = server.getHost();
        List<DiscoveredService> discovered = new ArrayList<>();

        // FIX-RATELIMIT: 记录本次扫描时间（扫描开始时即记录，防止并发多次触发）
        lastScanTime.put(server.getId(), System.currentTimeMillis());

        // 并行扫描所有端口
        List<Future<DiscoveredService>> futures = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : PORT_SERVICE_MAP.entrySet()) {
            int port = entry.getKey();
            String serviceType = entry.getValue();
            futures.add(scanPool.submit(() -> {
                if (isPortOpen(host, port)) {
                    // v2.12: 协议探针验证 — 确认端口上运行的是否真的是预期服务
                    String verified = probeServiceIdentity(host, port, serviceType);
                    if (verified != null) {
                        return DiscoveredService.builder()
                                .serverId(server.getId())
                                .serverName(server.getName())
                                .host(host)
                                .port(port)
                                .serviceType(verified)
                                .exporterType(verified)
                                .status("CONFIRMED")   // 协议验证通过
                                .build();
                    } else {
                        // 端口开放但协议不匹配 → 标记为疑似
                        return DiscoveredService.builder()
                                .serverId(server.getId())
                                .serverName(server.getName())
                                .host(host)
                                .port(port)
                                .serviceType(serviceType)
                                .exporterType(serviceType)
                                .status("UNVERIFIED")   // 仅端口匹配，协议未确认
                                .build();
                    }
                }
                return null;
            }));
        }

        for (Future<DiscoveredService> f : futures) {
            try {
                DiscoveredService svc = f.get(CONNECT_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                if (svc != null) discovered.add(svc);
            } catch (Exception e) {
                // 超时或异常，跳过
            }
        }

        if (!discovered.isEmpty()) {
            log.info("[Discovery] 服务器 {} ({}) 发现 {} 个服务: {}",
                    server.getName(), host, discovered.size(),
                    discovered.stream().map(d -> d.getServiceType() + ":" + d.getPort()).toList());
        }

        return discovered;
    }

    /**
     * 强制扫描单台服务器（忽略冷却限制，用于手动调试）
     *
     * FIX-RATELIMIT: 提供独立的强制扫描方法，让 API 层可以选择是否绕过冷却
     * 只有 ADMIN 权限的接口应调用此方法
     */
    public List<DiscoveredService> scanServerForced(ServerNode server) {
        // 重置该服务器的冷却时间
        lastScanTime.remove(server.getId());
        return scanServer(server);
    }

    // ==================== 速率限制工具方法 ====================

    /**
     * FIX-RATELIMIT: 检查服务器是否在冷却时间内
     */
    private boolean isInCooldown(String serverId) {
        Long last = lastScanTime.get(serverId);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < SCAN_COOLDOWN_MS;
    }

    /**
     * FIX-RATELIMIT: 获取服务器剩余冷却秒数
     */
    private long remainingCooldownSeconds(String serverId) {
        Long last = lastScanTime.get(serverId);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0, (SCAN_COOLDOWN_MS - elapsed) / 1000);
    }

    /** 检测 TCP 端口是否开放 */
    private boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 获取端口→服务映射表 */
    public Map<Integer, String> getPortServiceMap() {
        return Collections.unmodifiableMap(PORT_SERVICE_MAP);
    }

    // ==================== 数据模型 ====================

    @Data
    @Builder
    public static class DiscoveredService {
        private String serverId;
        private String serverName;
        private String host;
        private int port;
        private String serviceType;
        private String exporterType;
        /**
         * v2.12: 发现状态
         * CONFIRMED  — 协议探针验证通过，确认是对应服务
         * UNVERIFIED — 端口开放但协议未验证（可能是其他服务占用该端口）
         * DISCOVERED — 旧兼容状态
         */
        private String status;
    }

    // ── v2.12: 协议探针验证 ─────────────────────────────────────

    /**
     * 对已开放的端口发送协议特征探针，验证是否真的是预期服务。
     *
     * 解决问题：
     * - 用户把 MySQL 端口改为 3307 → 3306 上可能跑着别的服务
     * - 常见端口 80/8080 被非 Nginx 服务占用
     * - Redis 改了默认端口但 6379 上跑了其他 TCP 服务
     *
     * @return 确认的服务类型名，null 表示探针未通过（不一定不是该服务，可能是认证拦截）
     */
    private String probeServiceIdentity(String host, int port, String expectedType) {
        try {
            switch (expectedType) {
                case "redis" -> {
                    return probeRedis(host, port) ? "redis" : null;
                }
                case "mysql" -> {
                    return probeMySQL(host, port) ? "mysql" : null;
                }
                case "postgres" -> {
                    return probePostgres(host, port) ? "postgres" : null;
                }
                case "nginx" -> {
                    return probeHTTP(host, port) ? "nginx" : null;
                }
                default -> {
                    // 其他服务类型暂无探针，返回 null（标记 UNVERIFIED）
                    return null;
                }
            }
        } catch (Exception e) {
            log.debug("[Discovery] 协议探针异常 {}:{} ({}): {}", host, port, expectedType, e.getMessage());
            return null;
        }
    }

    /** Redis 探针：发送 PING，期望收到 +PONG 或 -NOAUTH */
    private boolean probeRedis(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2000);
            s.getOutputStream().write("PING\r\n".getBytes());
            s.getOutputStream().flush();
            byte[] buf = new byte[64];
            int n = s.getInputStream().read(buf);
            if (n > 0) {
                String resp = new String(buf, 0, n).trim();
                // +PONG = 无密码的 Redis；-NOAUTH / -ERR = 有密码的 Redis
                return resp.startsWith("+PONG") || resp.startsWith("-NOAUTH")
                        || resp.startsWith("-ERR") || resp.contains("DENIED");
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * MySQL 探针：连接后 MySQL Server 主动发送 Greeting Packet
     * 前 4 字节是包头，第 5 字节是协议版本号（0x0a = MySQL 5.x/8.x）
     */
    private boolean probeMySQL(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2000);
            byte[] buf = new byte[128];
            int n = s.getInputStream().read(buf);
            if (n > 5) {
                // MySQL Greeting: buf[4] == 0x0a (protocol v10)
                // 或者 response 中包含 "mysql" / "MariaDB" 字符串
                if (buf[4] == 0x0a) return true;
                String greeting = new String(buf, 0, n);
                return greeting.toLowerCase().contains("mysql") || greeting.toLowerCase().contains("mariadb");
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * PostgreSQL 探针：发送 SSLRequest (8 bytes)
     * PostgreSQL 会回复 'N'（不支持 SSL）或 'S'（支持 SSL）
     * 非 PG 服务不会回复单字符 N/S
     */
    private boolean probePostgres(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2000);
            // SSLRequest packet: length(8) + SSLRequest code (80877103)
            byte[] sslReq = {0, 0, 0, 8, 0x04, (byte)0xd2, 0x16, 0x2f};
            s.getOutputStream().write(sslReq);
            s.getOutputStream().flush();
            int resp = s.getInputStream().read();
            return resp == 'N' || resp == 'S';
        } catch (Exception ignored) {}
        return false;
    }

    /** HTTP 探针：发送 HEAD /，检查响应是否包含 HTTP 协议标识 */
    private boolean probeHTTP(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2000);
            String req = "HEAD / HTTP/1.0\r\nHost: " + host + "\r\n\r\n";
            s.getOutputStream().write(req.getBytes());
            s.getOutputStream().flush();
            byte[] buf = new byte[256];
            int n = s.getInputStream().read(buf);
            if (n > 0) {
                String resp = new String(buf, 0, n);
                return resp.startsWith("HTTP/");
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * v2.10 P1-07 修复:注册线程池关闭钩子,避免 Spring DevTools 热重启/多次 @SpringBootTest 累积 → OOM
     */
    @PreDestroy
    public void shutdownThreadPool_v210() {
        try {
            if (scanPool != null && !scanPool.isShutdown()) {
                scanPool.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
}