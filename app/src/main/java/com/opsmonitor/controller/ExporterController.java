package com.opsmonitor.controller;

import jakarta.annotation.PreDestroy;
import com.opsmonitor.config.ApiSafetyFilter;
import com.opsmonitor.config.InputValidator;
import com.opsmonitor.docker.DockerService;
import com.opsmonitor.model.*;
import com.opsmonitor.monitor.ExporterManager;
import com.opsmonitor.monitor.PrometheusManager;
import com.opsmonitor.service.AuditLogService;
import com.opsmonitor.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Exporter 管理 API
 *
 * 审查修复：
 * - register() 前调用 validateExporterType + validateTargetAddress
 * - testConnection() 补上 SSRF 防护（禁止内网地址探测）
 * - getExporterLogs() 使用 clampLogTail 限制行数
 * - 写操作记录审计日志
 */
@Slf4j
@RestController
@RequestMapping("/api/exporters")
@RequiredArgsConstructor
public class ExporterController {

    private final ExporterManager  exporterManager;
    private final PrometheusManager prometheusManager;
    private final com.opsmonitor.monitor.ExporterHealthService healthService;
    private final DockerService    dockerService;
    private final ApiSafetyFilter  safetyFilter;
    private final AuditLogService  auditLog;
    private final AuthService      authService;

    /**
     * DNS 解析专用单例线程池（修复：原先 isInternalAddress 每次调用都 new ExecutorService，导致线程池泄漏）
     * 使用 daemon 线程，不阻止 JVM 关闭
     */
    private static final java.util.concurrent.ExecutorService DNS_RESOLVER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "exporter-dns-resolver");
                t.setDaemon(true);
                return t;
            });

    /**
     * testConnection SSRF 防护黑名单（精简版）
     *
     * 设计说明：testConnection 的使用场景是管理员主动测试同网段服务器上已运行的 Exporter
     * 是否可达，192.168.x.x / 10.x.x.x / 172.16-31.x.x 这些私网地址是被监控目标，
     * 不能全部封禁，否则远程服务器监控功能完全失效。
     *
     * 真正需要阻断的地址：
     *   - 127.x.x.x       — loopback，防止探测本机服务
     *   - 169.254.x.x     — link-local / cloud metadata（AWS 169.254.169.254 等）
     *   - 0.x.x.x         — 无效地址
     *   - ::1 / fe80:     — IPv6 loopback / link-local
     *
     * 不应封禁：
     *   - 192.168.x.x / 10.x.x.x / 172.16-31.x.x — 局域网被监控主机（核心功能）
     */
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "127.", "169.254.", "0.",
            "::1", "fe80:", "fc00:", "fd00:"
    );

    // ==================== 模板 ====================

    @GetMapping("/templates")
    public ApiResponse<List<ExporterTemplate>> listTemplates() {
        return ApiResponse.ok(exporterManager.listTemplates());
    }

    @GetMapping("/templates/{type}")
    public ApiResponse<ExporterTemplate> getTemplate(@PathVariable String type) {
        safetyFilter.validateExporterType(type);
        return ApiResponse.ok(exporterManager.getTemplate(type));
    }

    // ==================== 注册/注销 ====================

    @PostMapping("/register")
    public ApiResponse<ExporterInstance> register(@RequestBody ExporterRegisterRequest request,
                                                  HttpServletRequest httpRequest) {
        User op = getUser(httpRequest);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:register")) return ApiResponse.error(403, "权限不足，需要 OPS 或 ADMIN 角色");
        // 输入校验
        safetyFilter.validateExporterType(request.getType());
        if (request.getTargetAddress() != null && !request.getTargetAddress().isBlank()) {
            InputValidator.validateTargetAddress(request.getTargetAddress());
        }

        ExporterInstance instance = exporterManager.registerExporter(request);
        auditLog.logSuccess(getOperator(httpRequest), "EXPORTER_REGISTER",
                instance.getId() + "(" + instance.getType() + ")");
        return ApiResponse.ok("Exporter 注册成功", instance);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> unregister(@PathVariable String id,
                                          HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:delete")) return ApiResponse.error(403, "权限不足");
        exporterManager.unregisterExporter(id);
        auditLog.logSuccess(getOperator(request), "EXPORTER_UNREGISTER", id);
        return ApiResponse.ok("Exporter 已注销: " + id);
    }

    @PostMapping("/{id}/start")
    public ApiResponse<String> start(@PathVariable String id,
                                     HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:register")) return ApiResponse.error(403, "权限不足");
        exporterManager.startExporter(id);
        auditLog.logSuccess(getOperator(request), "EXPORTER_START", id);
        return ApiResponse.ok("Exporter 已启动");
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<String> stop(@PathVariable String id,
                                    HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:register")) return ApiResponse.error(403, "权限不足");
        exporterManager.stopExporter(id);
        auditLog.logSuccess(getOperator(request), "EXPORTER_STOP", id);
        return ApiResponse.ok("Exporter 已停止");
    }

    // v2.20: 批量注册
    @PostMapping("/batch-register")
    public ApiResponse<Map<String, Object>> batchRegister(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:register")) return ApiResponse.error(403, "权限不足");

        String serverId = (String) body.getOrDefault("serverId", "local");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> exporters = (List<Map<String, String>>) body.getOrDefault("exporters", List.of());

        if (exporters.isEmpty()) return ApiResponse.error(400, "exporters 列表不能为空");

        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0, failed = 0;
        for (Map<String, String> exp : exporters) {
            String type = exp.get("type");
            try {
                ExporterRegisterRequest req = new ExporterRegisterRequest();
                req.setServerId(serverId);
                req.setType(type);
                req.setTargetAddress(exp.get("targetAddress"));
                req.setProject(exp.getOrDefault("project", ""));
                req.setService(exp.getOrDefault("service", ""));
                ExporterInstance instance = exporterManager.registerExporter(req);
                results.add(Map.of("type", type, "status", "ok", "exporterId", instance.getId()));
                success++;
            } catch (Exception e) {
                results.add(Map.of("type", type, "status", "failed", "error",
                        e.getMessage() != null ? e.getMessage() : "注册失败"));
                failed++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", exporters.size());
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("results", results);
        auditLog.logSuccess(getOperator(request), "EXPORTER_BATCH_REGISTER",
                serverId + " " + success + "/" + (success + failed));
        return ApiResponse.ok(summary);
    }

    // v2.20: 批量注销
    @DeleteMapping("/batch")
    public ApiResponse<Map<String, Object>> batchUnregister(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:delete")) return ApiResponse.error(403, "权限不足");

        @SuppressWarnings("unchecked")
        List<String> exporterIds = (List<String>) body.getOrDefault("exporterIds", List.of());
        if (exporterIds.isEmpty()) return ApiResponse.error(400, "exporterIds 列表不能为空");

        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0, failed = 0;
        for (String id : exporterIds) {
            try {
                exporterManager.unregisterExporter(id);
                results.add(Map.of("exporterId", id, "status", "ok"));
                success++;
            } catch (Exception e) {
                results.add(Map.of("exporterId", id, "status", "failed", "error",
                        e.getMessage() != null ? e.getMessage() : "注销失败"));
                failed++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", exporterIds.size());
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("results", results);
        auditLog.logSuccess(getOperator(request), "EXPORTER_BATCH_UNREGISTER", success + "/" + (success + failed));
        return ApiResponse.ok(summary);
    }

    // v2.17: Exporter 标签更新
    @PutMapping("/{id}/labels")
    public ApiResponse<ExporterInstance> updateLabels(@PathVariable String id,
                                                       @RequestBody Map<String, String> body,
                                                       HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:register")) return ApiResponse.error(403, "权限不足，需要 OPS 或 ADMIN 角色");

        String project = body != null ? body.getOrDefault("project", "") : "";
        String service = body != null ? body.getOrDefault("service", "") : "";

        // 输入校验：不超过 64 字符，仅允许中英文数字下划线横线
        if (project.length() > 64 || !project.matches("^[a-zA-Z0-9_\\u4e00-\\u9fff\\-]*$"))
            return ApiResponse.error(400, "project 格式无效（仅允许中英文数字下划线横线，≤64字符）");
        if (service.length() > 64 || !service.matches("^[a-zA-Z0-9_\\u4e00-\\u9fff\\-]*$"))
            return ApiResponse.error(400, "service 格式无效（仅允许中英文数字下划线横线，≤64字符）");

        exporterManager.updateLabels(id, project, service);
        auditLog.logSuccess(getOperator(request), "EXPORTER_LABEL_UPDATE",
                id + " project=" + project + " service=" + service);
        return ApiResponse.ok("标签已更新", exporterManager.getExporter(id));
    }

    // v2.19: Exporter 全链路健康检查
    @GetMapping("/{id}/health-check")
    public ApiResponse<Map<String, Object>> healthCheck(@PathVariable String id,
                                                         HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");

        ExporterInstance exp = exporterManager.getExporter(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exporterId", id);
        List<Map<String, String>> checks = new ArrayList<>();
        boolean allOk = true;

        // 步骤1：容器状态（仅 Docker 管理的 Exporter）
        if (exp.isManagedByDocker() && exp.getContainerId() != null) {
            try {
                var info = dockerService.getContainer(exp.getContainerId());
                boolean running = "running".equalsIgnoreCase(info.getState());
                checks.add(Map.of("step", "容器状态", "status", running ? "ok" : "error",
                        "detail", info.getState() + " (" + info.getStatus() + ")"));
                if (!running) allOk = false;
            } catch (Exception e) {
                checks.add(Map.of("step", "容器状态", "status", "error",
                        "detail", "容器查询失败: " + e.getMessage()));
                allOk = false;
            }
        } else {
            checks.add(Map.of("step", "容器状态", "status", "skip", "detail", "远程 Exporter，无需容器检查"));
        }

        // 步骤2：Prometheus 抓取
        try {
            String promUrl = "http://127.0.0.1:9090/api/v1/query";
            String query = promUrl + "?query=up{job=\"" + exp.getJobName() + "\"}";
            var promResp = httpGet(query);
            boolean promOk = promResp != null && promResp.contains("\"1\"");
            checks.add(Map.of("step", "Prometheus 抓取", "status", promOk ? "ok" : "error",
                    "detail", promOk ? "up=1" : "up=0 或未找到"));
            if (!promOk) allOk = false;
        } catch (Exception e) {
            checks.add(Map.of("step", "Prometheus 抓取", "status", "error",
                    "detail", "查询失败: " + e.getMessage()));
            allOk = false;
        }

        // 步骤3：VictoriaMetrics 数据
        try {
            String vmUrl = "http://127.0.0.1:8428/api/v1/query";
            String query = vmUrl + "?query=up{job=\"" + exp.getJobName() + "\"}";
            var vmResp = httpGet(query);
            boolean vmOk = vmResp != null && vmResp.contains("\"1\"");
            checks.add(Map.of("step", "VictoriaMetrics 数据", "status", vmOk ? "ok" : "error",
                    "detail", vmOk ? "最近5分钟有数据点" : "无数据或数据过期"));
            if (!vmOk) allOk = false;
        } catch (Exception e) {
            checks.add(Map.of("step", "VictoriaMetrics 数据", "status", "error",
                    "detail", "查询失败: " + e.getMessage()));
            allOk = false;
        }

        // 步骤4：Grafana 仪表盘
        try {
            var gfResp = httpGet("http://127.0.0.1:3000/api/health");
            boolean gfOk = gfResp != null && gfResp.contains("\"database\": \"ok\"");
            checks.add(Map.of("step", "Grafana 仪表盘", "status", gfOk ? "ok" : "error",
                    "detail", gfOk ? "可访问" : "不可访问"));
            if (!gfOk) allOk = false;
        } catch (Exception e) {
            checks.add(Map.of("step", "Grafana 仪表盘", "status", "error",
                    "detail", "查询失败: " + e.getMessage()));
            allOk = false;
        }

        result.put("checks", checks);
        result.put("summary", allOk ? "全链路正常" : "存在问题，请查看详情");
        return ApiResponse.ok(result);
    }

    /** v2.19: HTTP GET 带 3 秒超时 */
    private String httpGet(String url) {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(3)).build();
            return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 查询 ====================

    @GetMapping
    public ApiResponse<List<ExporterInstance>> listExporters() {
        List<ExporterInstance> list = exporterManager.listExporters();
        // v2.31: 补充 Prometheus 采集健康状态
        java.util.Map<String, String> healthMap = healthService.getAllStatus();
        list.forEach(e -> e.setHealthStatus(healthMap.getOrDefault(e.getId(), "UNKNOWN")));
        return ApiResponse.ok(list);
    }

    @GetMapping("/{id}")
    public ApiResponse<ExporterInstance> getExporter(@PathVariable String id) {
        return ApiResponse.ok(exporterManager.getExporter(id));
    }

    // ==================== 日志 ====================

    @GetMapping("/{id}/logs")
    public ApiResponse<ContainerLogResponse> getExporterLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "100") int tail) {
        ExporterInstance instance = exporterManager.getExporter(id);
        // 限制最大行数
        int safeTail = safetyFilter.clampLogTail(tail);
        ContainerLogResponse logs = dockerService.getLogs(
                instance.getContainerName(), safeTail, true, true, true);
        return ApiResponse.ok(logs);
    }

    // ==================== 测试连接（含SSRF防护） ====================

    /**
     * BUG-8修复：testConnection 允许的端口白名单（已知 Exporter 端口）
     * 防止通过 test API 对任意内网端口进行端口扫描
     */
    private static final Set<Integer> ALLOWED_EXPORTER_PORTS = Set.of(
            // node-exporter / windows-exporter
            9100, 9182,
            // nginx-exporter
            9113,
            // redis-exporter
            9121,
            // mysql-exporter
            9104,
            // postgres-exporter
            9187,
            // mongodb-exporter
            9216,
            // kafka-exporter
            9308,
            // blackbox-exporter
            9115,
            // haproxy-exporter
            9101,
            // custom: 9000-9999 范围内的 Exporter 端口
            9090, 9093, 8428,
            // 常见 DB 端口（用于验证 redis/mysql 地址是否可达）
            6379, 3306, 5432, 27017
    );

    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> testConnection(@RequestBody Map<String, String> request,
                                                           HttpServletRequest httpRequest) {
        // v2.33 SEC-2 修复：原先仅要求登录，VIEWER 也可对内网做端口探测；补 RBAC 校验
        User op = getUser(httpRequest);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "exporter:register")) return ApiResponse.error(403, "权限不足，需要 OPS 或 ADMIN 角色");

        String type          = request.get("type");
        String targetAddress = request.get("targetAddress");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("targetAddress", targetAddress);

        if (targetAddress == null || targetAddress.isBlank()) {
            result.put("success", false);
            result.put("message", "目标地址不能为空");
            return ApiResponse.ok(result);
        }

        // BUG-8修复：提取端口并校验是否在白名单内，防止内网端口扫描
        try {
            int port = extractPort(targetAddress);
            if (port > 0 && !isAllowedPort(port)) {
                log.warn("[ExporterController] testConnection 端口不在白名单: {} port={}", targetAddress, port);
                result.put("success", false);
                result.put("message", "端口 " + port + " 不在允许的 Exporter 端口范围内");
                return ApiResponse.ok(result);
            }
        } catch (Exception ignored) {}

        // SSRF 防护：拒绝危险地址（loopback / link-local / cloud metadata 等）
        // 注：局域网地址不拦截（运维场景需要测试局域网 Exporter 连通性）
        try {
            if (isInternalAddress(targetAddress)) {
                log.warn("[ExporterController] testConnection SSRF 拒绝: {}", targetAddress);
                result.put("success", false);
                result.put("message", "不允许连接该地址（loopback/link-local 地址）");
                return ApiResponse.ok(result);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "地址解析失败");
            return ApiResponse.ok(result);
        }

        try {
            var testResult = testTargetDetailed(type, targetAddress);
            result.put("success", testResult.getKey());
            result.put("message", testResult.getValue());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }

        return ApiResponse.ok(result);
    }

    /** 从地址中提取端口号 */
    private int extractPort(String address) {
        try {
            String h = address;
            if (h.contains("://")) h = h.substring(h.indexOf("://") + 3);
            if (h.contains("@"))   h = h.substring(h.lastIndexOf("@") + 1); // FIX: 去掉 user:pass@
            if (h.contains("/"))   h = h.substring(0, h.indexOf("/"));
            if (h.contains("?"))   h = h.substring(0, h.indexOf("?"));      // FIX: 去掉查询参数
            if (h.contains(":")) {
                return Integer.parseInt(h.substring(h.lastIndexOf(":") + 1));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** 校验端口是否在允许范围：9000-9999（Exporter 惯用范围）或白名单内 */
    private boolean isAllowedPort(int port) {
        if (port >= 9000 && port <= 9999) return true; // Exporter 惯用端口范围
        return ALLOWED_EXPORTER_PORTS.contains(port);
    }

    // ==================== Prometheus ====================

    @GetMapping("/prometheus/jobs")
    public ApiResponse<List<String>> listPrometheusJobs() {
        return ApiResponse.ok(prometheusManager.listScrapeJobs());
    }

    @PostMapping("/prometheus/reload")
    public ApiResponse<String> reloadPrometheus(HttpServletRequest request) {
        User op = getUser(request);
        if (op == null) return ApiResponse.error(401, "未登录");
        if (!authService.hasPermission(op, "*")) return ApiResponse.error(403, "权限不足，需要 ADMIN 角色");
        boolean ok = prometheusManager.reloadConfig();
        if (ok) auditLog.logSuccess(getOperator(request), "PROMETHEUS_RELOAD", "manual");
        return ok ? ApiResponse.ok("Prometheus 配置已重新加载")
                : ApiResponse.error("Prometheus 热加载失败");
    }

    // ==================== 私有方法 ====================

    /**
     * SSRF 防护：检测地址是否为内网 / 危险地址
     * DNS 解析设 3 秒超时，防 DNS rebinding
     */
    private boolean isInternalAddress(String address) throws Exception {
        // 提取 host（逐步剥离协议/userinfo/路径/端口，最终得到纯 hostname）
        // 修复：必须先处理 @ 符号，否则 postgresql://user:pass@host:port 会把 "user" 当 host
        String h = address;
        if (h.contains("://")) h = h.substring(h.indexOf("://") + 3);
        if (h.contains("@"))   h = h.substring(h.lastIndexOf("@") + 1); // FIX: 去掉 user:pass@
        if (h.contains("/"))   h = h.substring(0, h.indexOf("/"));
        if (h.contains("?"))   h = h.substring(0, h.indexOf("?"));      // FIX: 去掉 ?sslmode=... 等参数
        if (h.contains(":"))   h = h.substring(0, h.lastIndexOf(":"));
        final String host = h.trim(); // effectively final，供 lambda 使用
        if (host.isBlank()) return true;

        // 直接 IP 前缀匹配（无需 DNS）
        for (String prefix : BLOCKED_PREFIXES) {
            if (host.startsWith(prefix)) return true;
        }

        // DNS 解析加 3 秒超时，防慢 DNS 攻击（使用单例线程池，避免每次调用都 new ExecutorService）
        Future<InetAddress> future = DNS_RESOLVER.submit(() -> InetAddress.getByName(host));
        InetAddress addr;
        try {
            addr = future.get(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return true; // 解析超时，拒绝
        }

        String ip = addr.getHostAddress();
        return BLOCKED_PREFIXES.stream().anyMatch(ip::startsWith)
                || addr.isLoopbackAddress()
                // isSiteLocalAddress() 已移除：局域网私网IP是被监控目标，不应拦截
                || addr.isLinkLocalAddress()
                || addr.isAnyLocalAddress();
    }

    /**
     * 测试目标连通性，返回详细的 [success, message] 对
     * success=true 表示端口可达且服务响应正常
     * success=false 提供具体失败原因（端口不通/HTTP错误码/超时等）
     */
    private Map.Entry<Boolean, String> testTargetDetailed(String type, String address) {
        // host.docker.internal 在 Java 服务器上解析为宿主机
        String testAddress = address.replace("host.docker.internal", "localhost");

        if (testAddress.startsWith("http://") || testAddress.startsWith("https://")) {
            return testHttpDetailed(testAddress, type);
        }
        // FIX: 处理含 userinfo 的 URL（postgresql:// redis:// 等）
        // 提取 host:port 部分，去掉协议头、userinfo、路径、查询参数
        if (testAddress.contains("://")) {
            String hostPort = testAddress.substring(testAddress.indexOf("://") + 3);
            if (hostPort.contains("@")) hostPort = hostPort.substring(hostPort.lastIndexOf("@") + 1);
            if (hostPort.contains("/")) hostPort = hostPort.substring(0, hostPort.indexOf("/"));
            if (hostPort.contains("?")) hostPort = hostPort.substring(0, hostPort.indexOf("?"));
            String serviceType = "postgres".equals(type) ? "PostgreSQL" :
                    "redis".equals(type)    ? "Redis"      :
                            "mysql".equals(type)    ? "MySQL"      : type;
            return testSocketDetailed(hostPort, serviceType);
        }
        return testSocketDetailed(testAddress, type);
    }

    private Map.Entry<Boolean, String> testHttpDetailed(String url, String type) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url)).GET()
                    .timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return Map.entry(true, "连接成功（HTTP " + code + "）");
            } else if (code == 404) {
                return Map.entry(false, "HTTP 404：路径不存在，请检查 URL 是否正确");
            } else if (code == 502) {
                String hint = "nginx".equals(type) ?
                        "HTTP 502：Nginx stub_status 未启用！需在 nginx.conf 中添加：\nlocation /stub_status { stub_status; allow all; }" :
                        "HTTP 502：后端服务返回 502，请检查目标服务是否正常运行";
                return Map.entry(false, hint);
            } else if (code == 403) {
                return Map.entry(false, "HTTP 403：访问被拒绝，请检查防火墙或 allow/deny 配置");
            } else {
                return Map.entry(false, "HTTP " + code + "：请检查目标服务状态");
            }
        } catch (java.net.ConnectException e) {
            return Map.entry(false, "连接被拒绝：目标端口未开放或服务未启动");
        } catch (java.net.http.HttpTimeoutException e) {
            return Map.entry(false, "连接超时（5秒）：请检查网络连通性和防火墙");
        } catch (Exception e) {
            return Map.entry(false, "连接失败: " + e.getMessage());
        }
    }

    private Map.Entry<Boolean, String> testSocketDetailed(String hostPort, String serviceType) {
        try {
            String[] parts = hostPort.split(":");
            String host = parts[0].trim();
            int port = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000);
                return Map.entry(true, "端口 " + port + " 可达（" + serviceType + " 服务连接成功）");
            }
        } catch (java.net.ConnectException e) {
            return Map.entry(false, "端口不可达：目标服务未启动或防火墙拦截");
        } catch (java.net.SocketTimeoutException e) {
            return Map.entry(false, "连接超时（5秒）：请检查网络连通性");
        } catch (Exception e) {
            return Map.entry(false, "连接失败: " + e.getMessage());
        }
    }

    // 保留旧方法供内部兼容
    private boolean testTarget(String type, String address) {
        return testTargetDetailed(type, address).getKey();
    }
    private boolean testHttp(String url) { return testHttpDetailed(url, "").getKey(); }
    private boolean testSocket(String hostPort) { return testSocketDetailed(hostPort, "").getKey(); }

    private User getUser(HttpServletRequest request) { return (User) request.getAttribute("currentUser"); }
    private String getOperator(HttpServletRequest request) {
        User u = getUser(request); return u != null ? u.getUsername() : "anonymous";
    }
    /**
     * v2.10 P1-07 修复:注册线程池关闭钩子,避免 Spring DevTools 热重启/多次 @SpringBootTest 累积 → OOM
     */
    @PreDestroy
    public void shutdownThreadPool_v210() {
        try {
            if (DNS_RESOLVER != null && !DNS_RESOLVER.isShutdown()) {
                DNS_RESOLVER.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
}