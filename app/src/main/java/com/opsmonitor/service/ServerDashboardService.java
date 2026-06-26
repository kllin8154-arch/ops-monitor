package com.opsmonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.model.ServerNode;
import com.opsmonitor.monitor.ExporterManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 服务器独立仪表盘服务
 *
 * 功能：
 * 1. 每台服务器生成专属 Grafana 仪表盘（server-{id}.json）
 * 2. 自动检测服务端口（异步，不阻塞注册）
 * 3. 新增/删除服务器时自动触发仪表盘更新（增量，不覆盖已有面板）
 * 4. 提供端口检测结果查询 API
 * 5. 定时（每5分钟）重新扫描所有服务器端口
 *
 * 去重策略：
 * - 仪表盘 UID = "ops-server-{serverId}"，Grafana provisioning 会自动覆盖同 UID 的旧版本
 * - 文件名 = "server-{serverId}.json"，不会产生重复文件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerDashboardService {

    private final OpsMonitorProperties properties;
    private final ServerService         serverService;
    private final ExporterManager       exporterManager;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 端口扫描结果缓存: serverId → PortScanResult */
    private final ConcurrentHashMap<String, PortScanResult> scanCache = new ConcurrentHashMap<>();

    /**
     * 端口 → 疑似服务名映射（仅供端口扫描时的初始推断）
     *
     * v2.12 重要说明：
     *   端口开放 ≠ 服务确认。此映射仅用于"初始猜测"：
     *   - 用户可能修改了服务默认端口（如 MySQL 改为 3307 → 3306 上不是 MySQL）
     *   - 常见端口可能被其他服务占用（如 8080 上跑的不一定是 GeoServer）
     *
     *   确认服务身份的方式：
     *   1. ExporterDiscoveryService 的协议探针验证（CONFIRMED vs UNVERIFIED）
     *   2. 与已注册 Exporter 的 targetAddress 交叉比对
     *   3. 前端展示时明确标注"疑似"或"已确认"
     */
    @SuppressWarnings("serial")
    private static final LinkedHashMap<Integer, String> PORT_MAP = new LinkedHashMap<>() {{
        put(22,    "SSH");
        put(80,    "HTTP/Nginx");
        put(443,   "HTTPS/Nginx");
        put(8080,  "HTTP-Alt/GeoServer");
        put(8088,  "HTTP-Alt");
        put(8443,  "HTTPS-Alt");
        put(3306,  "MySQL");
        put(5432,  "PostgreSQL");
        put(1521,  "Oracle");
        put(1433,  "SQLServer");
        put(5236,  "DM达梦");
        put(54321, "KingBase人大金仓");
        put(6379,  "Redis");
        put(27017, "MongoDB");
        put(9200,  "Elasticsearch");
        put(9092,  "Kafka/JMX");
        put(2181,  "ZooKeeper");
        put(3000,  "Grafana");
        put(9090,  "Prometheus");
        put(9100,  "node_exporter");
        put(9182,  "windows_exporter");
        put(9104,  "mysqld_exporter");
        put(9187,  "postgres_exporter");
    }};

    /** TCP 扫描超时（ms） */
    private static final int SCAN_TIMEOUT_MS = 1500;

    private final ExecutorService scanPool = Executors.newFixedThreadPool(
            8, r -> { Thread t = new Thread(r,"port-scan"); t.setDaemon(true); return t; });

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        // 启动时为所有已注册服务器生成/更新仪表盘
        generateAllServerDashboards();
        // 异步扫描所有服务器端口
        scanAllServersAsync();
    }

    // ==================== 仪表盘生成 ====================

    /**
     * 为所有已注册服务器生成仪表盘（去重：按 serverId 命名）
     * 同时清理已删除服务器的旧仪表盘文件
     */
    public synchronized void generateAllServerDashboards() {
        List<ServerNode> servers = serverService.listServers();
        Set<String> activeIds = servers.stream()
                .map(ServerNode::getId).collect(Collectors.toSet());
        Path dashDir = getDashboardDir();

        // 清理已删除服务器的仪表盘
        try {
            Files.createDirectories(dashDir);
            Files.list(dashDir)
                    .filter(p -> p.getFileName().toString().startsWith("server-"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String sid  = name.replace("server-","").replace(".json","");
                        if (!activeIds.contains(sid)) {
                            try { Files.deleteIfExists(p); log.info("[Dashboard] 已清理旧仪表盘: {}", name); }
                            catch (IOException ignored) {}
                        }
                    });
        } catch (IOException e) {
            log.error("[Dashboard] 清理旧仪表盘失败: {}", e.getMessage());
        }

        // 为每台服务器生成仪表盘
        for (ServerNode server : servers) {
            try {
                generateServerDashboard(server);
            } catch (Exception e) {
                log.error("[Dashboard] 生成仪表盘失败 {}: {}", server.getName(), e.getMessage());
            }
        }
        log.info("[Dashboard] 已为 {} 台服务器生成专属仪表盘", servers.size());
    }

    /**
     * 新增服务器后异步触发仪表盘生成 + 端口扫描
     */
    @Async
    public void onServerAdded(ServerNode server) {
        log.info("[Dashboard] 新服务器注册，自动生成仪表盘: {}", server.getName());
        try {
            generateServerDashboard(server);
            scanServerPorts(server);
        } catch (Exception e) {
            log.error("[Dashboard] 服务器 {} 初始化失败: {}", server.getName(), e.getMessage());
        }
    }

    /**
     * 服务器重命名后更新仪表盘
     */
    @Async
    public void onServerUpdated(ServerNode server) {
        try { generateServerDashboard(server); }
        catch (Exception e) { log.error("[Dashboard] 更新仪表盘失败: {}", e.getMessage()); }
    }

    /**
     * 服务器删除后清理仪表盘文件
     */
    public void onServerDeleted(String serverId) {
        Path f = getDashboardDir().resolve("server-" + serverId + ".json");
        try { Files.deleteIfExists(f); log.info("[Dashboard] 已删除仪表盘: server-{}.json", serverId); }
        catch (IOException e) { log.warn("[Dashboard] 删除仪表盘失败: {}", e.getMessage()); }
    }

    /**
     * 为单台服务器生成专属仪表盘
     *
     * 仪表盘特性：
     * - 自动检测 OS 类型（通过 exporter_type = node/windows）
     * - 同时包含 Linux + Windows 面板，有数据的自然显示，无数据不显示（No data 静默）
     * - 标题 = 服务器显示名称（可编辑）
     * - UID 唯一 = ops-server-{serverId}（防重复）
     */
    /**
     * V6 架构调整：服务器专属仪表盘已废弃
     *
     * 原因：专属仪表盘（server-{id}.json）与"基础设施总览"高度重叠，
     * 导致 Grafana 仪表盘列表臃肿（Grafana 截图显示有 8 个仪表盘）。
     *
     * 新架构：通过"基础设施总览"（infra-overview.json）的 server_name 变量
     * 下拉选择服务器，一个仪表盘覆盖所有服务器，无需为每台服务器单独生成文件。
     *
     * 此方法保留签名（避免修改调用链），但跳过文件生成。
     * onServerDeleted() 仍会清理旧的 server-{id}.json 文件。
     */
    /**
     * V6: 服务器专属仪表盘已废弃，此方法为空操作。
     * 改用"基础设施总览"仪表盘 + server_name 变量筛选服务器。
     */
    private void generateServerDashboard(ServerNode server) throws IOException {
        log.debug("[Dashboard] V6 已禁用服务器专属仪表盘: {} （由基础设施总览统一覆盖）", server.getName());
    }

    /**
     * 构建单服务器仪表盘 JSON 结构
     */
    private Map<String, Object> buildServerDashboard(
            String serverId, String serverName, boolean isLocal,
            boolean hasWindows, boolean hasNode) {

        String uid   = "ops-server-" + serverId;
        String title = "🖥 " + serverName + " 监控";

        // CPU PromQL（自动适配 Linux/Windows）
        String cpuExprL = "100 - avg by(instance)(rate(node_cpu_seconds_total{mode=\"idle\",server_name=\"" + serverName + "\"}[5m])) * 100";
        String cpuExprW = "100 - sum by(instance)(rate(windows_cpu_time_total{mode=\"idle\",server_name=\"" + serverName + "\"}[5m])) / sum by(instance)(rate(windows_cpu_time_total{server_name=\"" + serverName + "\"}[5m])) * 100";
        String memExprL = "(1 - node_memory_MemAvailable_bytes{server_name=\"" + serverName + "\"} / node_memory_MemTotal_bytes{server_name=\"" + serverName + "\"}) * 100";
        String memExprW = "(1 - windows_os_physical_memory_free_bytes{server_name=\"" + serverName + "\"} / windows_cs_physical_memory_bytes{server_name=\"" + serverName + "\"}) * 100";
        String dskExprL = "min by(instance)(node_filesystem_avail_bytes{mountpoint=\"/\",server_name=\"" + serverName + "\"} / node_filesystem_size_bytes{mountpoint=\"/\",server_name=\"" + serverName + "\"} * 100)";
        String dskExprW = "min by(instance,volume)(windows_logical_disk_free_bytes{volume!~\"HarddiskVolume.*\",server_name=\"" + serverName + "\"} / windows_logical_disk_size_bytes{volume!~\"HarddiskVolume.*\",server_name=\"" + serverName + "\"} * 100)";

        List<Map<String,Object>> panels = new ArrayList<>();
        int panelId = 1;

        // ─── 行1：CPU / 内存 / 磁盘 仪表盘 ───
        List<Map<String,Object>> cpuTargets = new ArrayList<>();
        if (hasNode)    cpuTargets.add(tgt(cpuExprL, "CPU(Linux)", "A"));
        if (hasWindows) cpuTargets.add(tgt(cpuExprW, "CPU(Windows)", "B"));
        panels.add(gauge(panelId++, "CPU 使用率", 0, 0, 6, 8, cpuTargets,
                steps(g(null), y(50), r(80))));

        List<Map<String,Object>> memTargets = new ArrayList<>();
        if (hasNode)    memTargets.add(tgt(memExprL, "内存(Linux)", "A"));
        if (hasWindows) memTargets.add(tgt(memExprW, "内存(Windows)", "B"));
        panels.add(gauge(panelId++, "内存使用率", 6, 0, 6, 8, memTargets,
                steps(g(null), y(60), r(85))));

        List<Map<String,Object>> dskTargets = new ArrayList<>();
        if (hasNode)    dskTargets.add(tgt(dskExprL, "磁盘(Linux)", "A"));
        if (hasWindows) dskTargets.add(tgt(dskExprW, "磁盘(Windows)", "B"));
        panels.add(gauge(panelId++, "磁盘可用", 12, 0, 6, 8, dskTargets,
                steps(r(null), y(10), g(20))));

        // Exporter 在线状态
        panels.add(stat(panelId++, "服务在线状态", 18, 0, 6, 8,
                "up{managed_by=\"ops-monitor\",server_name=\"" + serverName + "\"}",
                "{{exporter_type}}",
                List.of(Map.of("type","value","options", Map.of(
                        "1", Map.of("text","在线 ✓","color","green"),
                        "0", Map.of("text","离线 ✗","color","red")))),
                steps(r(null), g(1))));

        // ─── 行2：CPU 趋势 / 内存趋势 ───
        List<Map<String,Object>> cpuTs = new ArrayList<>();
        if (hasNode)    cpuTs.add(tgt(cpuExprL, "CPU(Linux)", "A"));
        if (hasWindows) cpuTs.add(tgt(cpuExprW, "CPU(Windows)", "B"));
        panels.add(ts(panelId++, "CPU 使用率趋势", 0, 8, 12, 8, cpuTs, "percent"));

        List<Map<String,Object>> memTs = new ArrayList<>();
        if (hasNode) {
            memTs.add(tgt("node_memory_MemTotal_bytes{server_name=\"" + serverName + "\"}", "总内存", "A"));
            memTs.add(tgt("node_memory_MemTotal_bytes{server_name=\"" + serverName + "\"}-node_memory_MemAvailable_bytes{server_name=\"" + serverName + "\"}", "已使用", "B"));
        }
        if (hasWindows) {
            memTs.add(tgt("windows_cs_physical_memory_bytes{server_name=\"" + serverName + "\"}", "总内存(Win)", "C"));
            memTs.add(tgt("windows_cs_physical_memory_bytes{server_name=\"" + serverName + "\"}-windows_os_physical_memory_free_bytes{server_name=\"" + serverName + "\"}", "已使用(Win)", "D"));
        }
        panels.add(ts(panelId++, "内存使用趋势", 12, 8, 12, 8, memTs, "bytes"));

        // ─── 行3：磁盘 I/O ───
        List<Map<String,Object>> dioRTs = new ArrayList<>();
        if (hasNode) dioRTs.add(tgt("rate(node_disk_read_bytes_total{server_name=\"" + serverName + "\",device!~\"dm-.*|loop.*\"}[5m])", "{{device}} 读", "A"));
        if (hasWindows) dioRTs.add(tgt("rate(windows_logical_disk_read_bytes_total{volume!~\"HarddiskVolume.*\",server_name=\"" + serverName + "\"}[5m])", "{{volume}} 读(Win)", "B"));
        panels.add(ts(panelId++, "磁盘 I/O 读", 0, 16, 12, 8, dioRTs, "Bps"));

        List<Map<String,Object>> dioWTs = new ArrayList<>();
        if (hasNode) dioWTs.add(tgt("rate(node_disk_written_bytes_total{server_name=\"" + serverName + "\",device!~\"dm-.*|loop.*\"}[5m])", "{{device}} 写", "A"));
        if (hasWindows) dioWTs.add(tgt("rate(windows_logical_disk_write_bytes_total{volume!~\"HarddiskVolume.*\",server_name=\"" + serverName + "\"}[5m])", "{{volume}} 写(Win)", "B"));
        panels.add(ts(panelId++, "磁盘 I/O 写", 12, 16, 12, 8, dioWTs, "Bps"));

        // ─── 行4：网络 ───
        List<Map<String,Object>> netRTs = new ArrayList<>();
        if (hasNode) netRTs.add(tgt("rate(node_network_receive_bytes_total{server_name=\"" + serverName + "\",device!~\"lo|docker.*|br-.*|veth.*\"}[5m])", "{{device}} ↓", "A"));
        if (hasWindows) netRTs.add(tgt("rate(windows_net_bytes_received_total{nic!~\".*Loopback.*|.*isatap.*\",server_name=\"" + serverName + "\"}[5m])", "{{nic}} ↓(Win)", "B"));
        panels.add(ts(panelId++, "网络接收", 0, 24, 12, 8, netRTs, "Bps"));

        List<Map<String,Object>> netSTs = new ArrayList<>();
        if (hasNode) netSTs.add(tgt("rate(node_network_transmit_bytes_total{server_name=\"" + serverName + "\",device!~\"lo|docker.*|br-.*|veth.*\"}[5m])", "{{device}} ↑", "A"));
        if (hasWindows) netSTs.add(tgt("rate(windows_net_bytes_sent_total{nic!~\".*Loopback.*|.*isatap.*\",server_name=\"" + serverName + "\"}[5m])", "{{nic}} ↑(Win)", "B"));
        panels.add(ts(panelId++, "网络发送", 12, 24, 12, 8, netSTs, "Bps"));

        // ─── 行5：服务端口状态（仅远程服务器显示端口扫描结果面板）───
        if (!isLocal) {
            panels.add(Map.of(
                    "id", panelId++, "type", "text", "title", "📡 检测到的服务端口",
                    "description", "由 OpsMonitor 自动扫描，每5分钟刷新",
                    "gridPos", Map.of("h", 4, "w", 24, "x", 0, "y", 32),
                    "options", Map.of("content",
                            buildPortStatusMarkdown(serverId, serverName), "mode", "markdown")
            ));
        }

        // ─── Exporter 状态汇总表 ───
        panels.add(Map.of(
                "id", panelId, "type", "table",
                "title", "🔌 Exporter 状态汇总",
                "gridPos", Map.of("h", 6, "w", 24, "x", 0, "y", isLocal ? 32 : 36),
                "datasource", Map.of("type","prometheus","uid","VictoriaMetrics"),
                "targets", List.of(
                        Map.of("expr","up{managed_by=\"ops-monitor\",server_name=\"" + serverName + "\"}",
                                "format","table","instant",true,"refId","A")),
                "transformations", List.of(Map.of("id","organize","options",Map.of(
                        "excludeByName", Map.of("Time",true,"__name__",true,"managed_by",true,"server_id",true),
                        "renameByName",  Map.of("exporter_type","类型","instance","监控地址","Value","状态","job","Job")))),
                "fieldConfig", Map.of("overrides", List.of(Map.of(
                        "matcher", Map.of("id","byName","options","状态"),
                        "properties", List.of(
                                Map.of("id","mappings","value",List.of(Map.of("type","value","options",Map.of(
                                        "1",Map.of("text","在线 ✓","color","green"),
                                        "0",Map.of("text","离线 ✗","color","red"))))),
                                Map.of("id","custom.displayMode","value","color-background")))))
        ));

        // ─── 本机额外面板：Docker 容器监控 ───
        if (isLocal) {
            addDockerPanels(panels, panelId);
        }

        // 拼装仪表盘
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("uid",           uid);
        dashboard.put("title",         title);
        dashboard.put("tags",          List.of("ops-monitor", "server", serverId));
        dashboard.put("timezone",      "browser");
        dashboard.put("refresh",       "30s");
        dashboard.put("schemaVersion", 30);
        dashboard.put("time",          Map.of("from","now-1h","to","now"));
        dashboard.put("panels",        panels);
        return dashboard;
    }

    /**
     * 为本机仪表盘追加 Docker 容器监控面板
     *
     * 面板数据来源：
     * 1. cAdvisor 指标（container_cpu_usage_seconds_total / container_memory_usage_bytes）
     *    — 需要部署 cAdvisor 容器，若未部署则面板静默显示 No data
     * 2. OpsMonitor 管理的 Exporter up 状态（直接从 VictoriaMetrics 查询）
     *    — 不依赖 cAdvisor，始终可用
     *
     * 删除其他服务器的监控后，本面板自动反映最新状态（PromQL 动态查询）
     */
    private void addDockerPanels(List<Map<String, Object>> panels, int startId) {
        int id  = startId;
        int y   = 38; // 在 Exporter 状态表（y=32,h=6）之后

        // Docker 容器 CPU 趋势（cAdvisor，无则 No data）
        panels.add(ts(id++, "🐳 容器 CPU 使用率", 0, y, 12, 8,
                List.of(tgt(
                        "sum by(name)(rate(container_cpu_usage_seconds_total{image!=\"\",name!~\"POD|k8s_.*\"}[5m])) * 100",
                        "{{name}}", "A")),
                "percent"));

        // Docker 容器内存趋势（cAdvisor，无则 No data）
        panels.add(ts(id++, "🐳 容器内存使用", 12, y, 12, 8,
                List.of(tgt(
                        "container_memory_usage_bytes{image!=\"\",name!~\"POD|k8s_.*\"}",
                        "{{name}}", "A")),
                "bytes"));

        // OpsMonitor 管理的本机 Exporter 在线状态（始终可用）
        panels.add(Map.of(
                "id", id, "type", "table",
                "title", "🔌 本机 Exporter 在线状态（实时）",
                "description", "删除/新增 Exporter 后自动刷新",
                "gridPos", Map.of("h", 8, "w", 24, "x", 0, "y", y + 8),
                "datasource", Map.of("type","prometheus","uid","VictoriaMetrics"),
                "targets", List.of(Map.of(
                        "expr",   "up{managed_by=\"ops-monitor\",server_id=\"local\"}",
                        "format", "table", "instant", true, "refId", "A")),
                "transformations", List.of(Map.of("id","organize","options",Map.of(
                        "excludeByName", Map.of("Time",true,"__name__",true,"managed_by",true,"server_id",true),
                        "renameByName",  Map.of("exporter_type","Exporter类型","instance","采集地址","Value","状态","job","Job名")))),
                "fieldConfig", Map.of("overrides", List.of(Map.of(
                        "matcher",    Map.of("id","byName","options","状态"),
                        "properties", List.of(
                                Map.of("id","mappings","value",List.of(Map.of("type","value","options",Map.of(
                                        "1", Map.of("text","在线 ✓","color","green"),
                                        "0", Map.of("text","离线 ✗","color","red"))))),
                                Map.of("id","custom.displayMode","value","color-background")))))));
    }

    private String buildPortStatusMarkdown(String serverId, String serverName) {
        PortScanResult result = scanCache.get(serverId);
        if (result == null) return "_端口扫描中，请稍候..._";
        StringBuilder sb = new StringBuilder();
        sb.append("| 端口 | 服务 | 状态 | 扫描时间 |\n");
        sb.append("|------|------|------|----------|\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        String scanTime = result.scanTime.format(fmt);
        result.ports.forEach((port, info) -> {
            String emoji = info.open ? "🟢 开放" : "🔴 关闭";
            sb.append(String.format("| %d | %s | %s | %s |\n",
                    port, info.service, emoji, scanTime));
        });
        return sb.toString();
    }

    // ==================== 端口扫描 ====================

    /**
     * 异步扫描所有服务器端口（启动时 + 定时5分钟）
     */
    @Async
    public void scanAllServersAsync() {
        serverService.listServers().forEach(s -> {
            if (!"local".equals(s.getId()) && s.getHost() != null)
                scanPool.submit(() -> scanServerPorts(s));
        });
    }

    /**
     * 定时每5分钟重新扫描（更新端口状态 + 刷新仪表盘 text 面板）
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scheduledPortScan() {
        scanAllServersAsync();
    }

    /**
     * 扫描单台服务器的常用端口
     */
    public PortScanResult scanServerPorts(ServerNode server) {
        String host = server.getHost();
        Map<Integer, PortInfo> portResults = new LinkedHashMap<>();
        List<Future<Map.Entry<Integer, PortInfo>>> futures = new ArrayList<>();

        for (Map.Entry<Integer, String> entry : PORT_MAP.entrySet()) {
            int port = entry.getKey();
            String svcName = entry.getValue();
            futures.add(scanPool.submit(() -> {
                boolean open = checkPort(host, port);
                return Map.entry(port, new PortInfo(port, svcName, open));
            }));
        }

        for (Future<Map.Entry<Integer, PortInfo>> f : futures) {
            try {
                Map.Entry<Integer, PortInfo> r = f.get(5, TimeUnit.SECONDS);
                portResults.put(r.getKey(), r.getValue());
            } catch (Exception ignored) {}
        }

        PortScanResult result = new PortScanResult(server.getId(), server.getName(),
                portResults, LocalDateTime.now());
        scanCache.put(server.getId(), result);
        log.info("[PortScan] {} ({}) 扫描完成，开放端口: {}",
                server.getName(), host,
                portResults.values().stream().filter(p -> p.open).map(p -> p.port + "").collect(Collectors.joining(",")));

        // 扫描完成后刷新仪表盘（更新 text 面板的端口状态）
        try { generateServerDashboard(server); } catch (Exception ignored) {}
        return result;
    }

    private boolean checkPort(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), SCAN_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 查询接口 ====================

    public List<PortScanResult> getAllScanResults() {
        return new ArrayList<>(scanCache.values());
    }

    public Optional<PortScanResult> getScanResult(String serverId) {
        return Optional.ofNullable(scanCache.get(serverId));
    }

    /**
     * 手动触发单台服务器端口扫描（API 调用）
     */
    @Async
    public void triggerScan(String serverId) {
        serverService.listServers().stream()
                .filter(s -> serverId.equals(s.getId()))
                .findFirst()
                .ifPresent(this::scanServerPorts);
    }

    // ==================== 辅助方法 ====================

    private Path getDashboardDir() {
        return Paths.get(properties.getCompose().getWorkDir(),
                "grafana", "provisioning", "dashboards").toAbsolutePath().normalize();
    }

    // ─── JSON 构建工具 ───

    private Map<String,Object> tgt(String expr, String legend, String refId) {
        return Map.of("expr", expr, "legendFormat", legend, "refId", refId);
    }

    private Map<String,Object> gauge(int id, String title, int x, int y, int w, int h,
                                     List<Map<String,Object>> targets, List<Map<String,Object>> steps) {
        return Map.of(
                "id", id, "type", "gauge", "title", title,
                "gridPos", Map.of("h",h,"w",w,"x",x,"y",y),
                "datasource", Map.of("type","prometheus","uid","VictoriaMetrics"),
                "targets", targets,
                "options", Map.of("reduceOptions", Map.of("calcs", List.of("lastNotNull"))),
                "fieldConfig", Map.of("defaults", Map.of(
                        "unit","percent","min",0,"max",100,
                        "thresholds", Map.of("mode","absolute","steps",steps),
                        "color", Map.of("mode","thresholds"))));
    }

    private Map<String,Object> ts(int id, String title, int x, int y, int w, int h,
                                  List<Map<String,Object>> targets, String unit) {
        return Map.of(
                "id", id, "type", "timeseries", "title", title,
                "gridPos", Map.of("h",h,"w",w,"x",x,"y",y),
                "datasource", Map.of("type","prometheus","uid","VictoriaMetrics"),
                "targets", targets,
                "fieldConfig", Map.of("defaults", Map.of("unit",unit,
                        "custom", Map.of("lineWidth",2,"fillOpacity",10),
                        "color", Map.of("mode","palette-classic"))),
                "options", Map.of("tooltip",Map.of("mode","multi"),
                        "legend",Map.of("displayMode","list","placement","bottom")));
    }

    private Map<String,Object> stat(int id, String title, int x, int y, int w, int h,
                                    String expr, String legend,
                                    List<Map<String,Object>> maps,
                                    List<Map<String,Object>> steps) {
        return Map.of(
                "id", id, "type", "stat", "title", title,
                "gridPos", Map.of("h",h,"w",w,"x",x,"y",y),
                "datasource", Map.of("type","prometheus","uid","VictoriaMetrics"),
                "targets", List.of(Map.of("expr",expr,"legendFormat",legend,"refId","A")),
                "options", Map.of("reduceOptions",Map.of("calcs",List.of("lastNotNull")),"orientation","auto"),
                "fieldConfig", Map.of("defaults", Map.of(
                        "mappings", maps,
                        "thresholds", Map.of("mode","absolute","steps",steps),
                        "color", Map.of("mode","thresholds"))));
    }

    private Map<String,Object> g(Object v) { return step("green", v); }
    private Map<String,Object> y(Object v) { return step("yellow", v); }
    private Map<String,Object> r(Object v) { return step("red", v); }
    private Map<String,Object> step(String color, Object value) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("color", color);
        m.put("value", value);
        return m;
    }
    private List<Map<String,Object>> steps(Map<String,Object>... s) { return Arrays.asList(s); }

    // ==================== 数据模型 ====================

    public static class PortScanResult {
        public final String serverId;
        public final String serverName;
        public final Map<Integer, PortInfo> ports;
        public final LocalDateTime scanTime;

        public PortScanResult(String serverId, String serverName,
                              Map<Integer, PortInfo> ports, LocalDateTime scanTime) {
            this.serverId   = serverId;
            this.serverName = serverName;
            this.ports      = ports;
            this.scanTime   = scanTime;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serverId",   serverId);
            m.put("serverName", serverName);
            m.put("scanTime",   scanTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            m.put("openPorts",  ports.values().stream()
                    .filter(p -> p.open)
                    .map(p -> Map.of("port", p.port, "service", p.service))
                    .collect(Collectors.toList()));
            m.put("allPorts",   ports.values().stream()
                    .map(p -> Map.of("port", p.port, "service", p.service, "open", p.open))
                    .collect(Collectors.toList()));
            return m;
        }
    }

    public static class PortInfo {
        public final int     port;
        public final String  service;
        public final boolean open;
        /**
         * v2.12: 服务验证状态
         * CONFIRMED  — 已通过协议探针或已注册 Exporter 交叉确认
         * UNVERIFIED — 仅端口匹配，未确认实际服务（可能误判）
         * REGISTERED — 有已注册的 Exporter 在此端口（最可靠）
         */
        public final String  verifyStatus;

        public PortInfo(int port, String service, boolean open) {
            this(port, service, open, open ? "UNVERIFIED" : "CLOSED");
        }

        public PortInfo(int port, String service, boolean open, String verifyStatus) {
            this.port = port; this.service = service; this.open = open;
            this.verifyStatus = verifyStatus;
        }
    }

    /** P2-2 fix: 关闭端口扫描线程池 */
    @jakarta.annotation.PreDestroy
    public void shutdownScanPool() {
        try { scanPool.shutdownNow(); } catch (Exception ignored) {}
    }
}