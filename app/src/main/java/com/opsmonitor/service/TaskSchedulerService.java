package com.opsmonitor.service;

import com.opsmonitor.model.OpsTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 任务编排服务（安全修复版 v2.3）
 *
 * P0-1 修复（原有）：
 * - SCRIPT 类型已禁用（命令注入风险）
 * - DOCKER 类型限制为白名单子命令（ps/logs/inspect/stats/restart/stop/start）
 * - HEALTHCHECK 校验 URL 格式
 * - 所有输入经过 shell 元字符过滤
 *
 * v2.3 新增修复（N1 + N2）：
 * - N1: DOCKER 任务参数额外白名单校验：
 *       1. 每个参数段长度上限 128 字符
 *       2. 参数段禁止包含路径穿越（../、./）
 *       3. 参数段禁止包含额外 shell 元字符（即使 ProcessBuilder 不走 shell，防御纵深）
 *       4. 参数总数上限 10 个
 * - N2: HEALTHCHECK SSRF 防护：
 *       解析目标 URL 的 IP，禁止访问内网段、loopback、link-local
 */
@Slf4j
@Service
public class TaskSchedulerService {

    private static final int MAX_HISTORY  = 200;
    private static final int POOL_SIZE    = 4;

    /** Docker 允许的子命令白名单 */
    private static final Set<String> DOCKER_ALLOWED_COMMANDS = Set.of(
            "ps", "logs", "inspect", "stats", "restart", "stop", "start", "images", "info", "version"
    );

    /** 危险 shell 元字符（对 payload 整体做检查） */
    private static final Pattern SHELL_INJECTION_PATTERN = Pattern.compile("[;|&$`(){}\\[\\]<>!\\\\]");

    // ===== N1: DOCKER 参数安全常量 =====
    /** 单个参数最大长度 */
    private static final int MAX_ARG_LENGTH = 128;
    /** 参数最大个数（含子命令） */
    private static final int MAX_ARG_COUNT  = 10;
    /** 路径穿越检测 */
    private static final Pattern PATH_TRAVERSAL = Pattern.compile("(\\.\\./|\\./)");
    /** 参数级额外非法字符（比 shell 元字符集更宽，防御纵深） */
    private static final Pattern ARG_ILLEGAL_CHARS = Pattern.compile("[;|&$`\"'\\\\<>]");

    // ===== N2: HEALTHCHECK SSRF 防护 =====
    /** 禁止访问的内网 IP 前缀 */
    private static final List<String> HC_BLOCKED_PREFIXES = List.of(
            "10.", "192.168.",
            "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
            "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
            "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
            "127.", "0.", "169.254.", "::1", "fd", "fc"
    );
    /** HEALTHCHECK 允许的协议 */
    private static final Set<String> HC_ALLOWED_SCHEMES = Set.of("http", "https");
    /** HEALTHCHECK URL 最大长度 */
    private static final int MAX_HC_URL_LENGTH = 512;

    private final ConcurrentLinkedDeque<OpsTask> taskHistory = new ConcurrentLinkedDeque<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE,
            r -> { Thread t = new Thread(r, "ops-task-worker"); t.setDaemon(true); return t; });

    /**
     * v2.10 P2-08 修复:DNS 解析单例线程池(原来每次 healthcheck 都 new→shutdown,浪费线程创建开销)
     * 复用 NotificationDispatcher.DNS_RESOLVER 相同模式
     */
    private static final ExecutorService DNS_RESOLVER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ops-task-dns-resolver");
                t.setDaemon(true);
                return t;
            });

    /**
     * v2.10 P1-07 修复:线程池生命周期钩子,避免 Spring DevTools 热重启累积 → OOM
     */
    @jakarta.annotation.PreDestroy
    public void shutdownThreadPool_v210() {
        try {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdownNow();
            }
            if (!DNS_RESOLVER.isShutdown()) {
                DNS_RESOLVER.shutdownNow();
            }
        } catch (Exception ignored) {}
    }

    public OpsTask submitTask(OpsTask task) {
        if (task.getId() == null) {
            task.setId(UUID.randomUUID().toString().substring(0, 8));
        }

        // 安全校验（含 N1/N2 新增）
        validateTask(task);

        task.setStatus("PENDING");
        task.setCreatedAt(System.currentTimeMillis());
        taskHistory.addFirst(task);
        trimHistory();

        executor.submit(() -> executeTask(task));
        log.info("任务已提交: {} ({})", task.getName(), task.getType());
        return task;
    }

    public List<OpsTask> listTasks(int limit) {
        return taskHistory.stream().limit(limit).toList();
    }

    public OpsTask getTask(String id) {
        return taskHistory.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    // ==================== 安全校验 ====================

    private void validateTask(OpsTask task) {
        if (task.getType() == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        if (task.getPayload() == null || task.getPayload().isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空");
        }

        String type = task.getType().toUpperCase();

        // SCRIPT 类型完全禁用
        if ("SCRIPT".equals(type)) {
            throw new IllegalArgumentException("SCRIPT 任务类型已禁用（安全策略），请使用 DOCKER 或 HEALTHCHECK");
        }

        // DOCKER 类型校验
        if ("DOCKER".equals(type)) {
            validateDockerPayload(task.getPayload());
        }

        // HEALTHCHECK 类型校验（N2: 增加 SSRF 防护）
        if ("HEALTHCHECK".equals(type)) {
            validateHealthCheckUrl(task.getPayload());
        }
    }

    /**
     * N1: Docker payload 深度校验
     * - shell 元字符检测（payload 整体）
     * - 子命令白名单
     * - 参数总数限制
     * - 单参数长度限制
     * - 路径穿越检测
     * - 参数级非法字符检测
     */
    private void validateDockerPayload(String payload) {
        String trimmed = payload.trim();

        // 整体 shell 元字符检测
        if (SHELL_INJECTION_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Docker 命令包含非法字符");
        }

        String[] parts = trimmed.split("\\s+");

        // 参数总数限制
        if (parts.length > MAX_ARG_COUNT) {
            throw new IllegalArgumentException("Docker 命令参数过多（最多 " + MAX_ARG_COUNT + " 个）");
        }

        // 子命令白名单
        String subCommand = parts[0].toLowerCase();
        if (!DOCKER_ALLOWED_COMMANDS.contains(subCommand)) {
            throw new IllegalArgumentException("Docker 子命令不在白名单: " + subCommand
                    + " (允许: " + DOCKER_ALLOWED_COMMANDS + ")");
        }

        // 逐参数深度校验
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];

            // 单参数长度
            if (arg.length() > MAX_ARG_LENGTH) {
                throw new IllegalArgumentException("Docker 命令参数过长（单个参数最大 " + MAX_ARG_LENGTH + " 字符）");
            }

            // 路径穿越
            if (PATH_TRAVERSAL.matcher(arg).find()) {
                throw new IllegalArgumentException("Docker 命令参数包含路径穿越序列");
            }

            // 参数级非法字符
            if (ARG_ILLEGAL_CHARS.matcher(arg).find()) {
                throw new IllegalArgumentException("Docker 命令参数包含非法字符: " + arg);
            }
        }
    }

    /**
     * N2: HEALTHCHECK URL SSRF 防护
     * - 协议白名单（仅 http/https）
     * - URL 长度限制
     * - DNS 解析后检测内网 IP
     * - 禁止访问 loopback / link-local / site-local / any-local
     */
    private void validateHealthCheckUrl(String rawUrl) {
        String url = rawUrl.trim();

        // 长度限制
        if (url.length() > MAX_HC_URL_LENGTH) {
            throw new IllegalArgumentException("HEALTHCHECK URL 过长（最大 " + MAX_HC_URL_LENGTH + " 字符）");
        }

        // 协议白名单
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的 HEALTHCHECK URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !HC_ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("HEALTHCHECK 仅支持 http/https 协议");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("HEALTHCHECK URL 缺少主机地址");
        }

        // 快速 IP 前缀检测（无需 DNS）
        for (String prefix : HC_BLOCKED_PREFIXES) {
            if (host.startsWith(prefix)) {
                throw new IllegalArgumentException("HEALTHCHECK 禁止访问内网/回环地址: " + host);
            }
        }

        // v2.10 P2-08 修复:DNS 解析使用单例静态池(原先每次 new → shutdownNow 浪费线程创建)
        // 参考 NotificationDispatcher.DNS_RESOLVER 模式
        try {
            Future<InetAddress> future = DNS_RESOLVER.submit(() -> InetAddress.getByName(host));
            InetAddress addr;
            try {
                addr = future.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalArgumentException("HEALTHCHECK URL DNS 解析超时，已拒绝");
            }

            String resolvedIp = addr.getHostAddress();

            // IP 段检测
            for (String prefix : HC_BLOCKED_PREFIXES) {
                if (resolvedIp.startsWith(prefix)) {
                    throw new IllegalArgumentException("HEALTHCHECK 禁止访问内网地址（解析到 " + resolvedIp + "）");
                }
            }

            // JDK 内置检测
            if (addr.isLoopbackAddress()) {
                throw new IllegalArgumentException("HEALTHCHECK 禁止访问回环地址");
            }
            if (addr.isLinkLocalAddress()) {
                throw new IllegalArgumentException("HEALTHCHECK 禁止访问链路本地地址");
            }
            if (addr.isSiteLocalAddress()) {
                throw new IllegalArgumentException("HEALTHCHECK 禁止访问站点本地（内网）地址");
            }
            if (addr.isAnyLocalAddress()) {
                throw new IllegalArgumentException("HEALTHCHECK 禁止访问任意本地地址");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("HEALTHCHECK URL 地址校验失败: " + e.getMessage());
        }
    }

    // ==================== 任务执行 ====================

    private void executeTask(OpsTask task) {
        task.setStatus("RUNNING");
        task.setStartedAt(System.currentTimeMillis());

        try {
            String result;
            switch (task.getType().toUpperCase()) {
                case "DOCKER"      -> result = executeDocker(task.getPayload());
                case "HEALTHCHECK" -> result = executeHealthCheck(task.getPayload());
                default -> {
                    task.setStatus("FAILED");
                    task.setResult("不支持的任务类型: " + task.getType());
                    return;
                }
            }
            task.setStatus("SUCCESS");
            task.setResult(result);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setResult("执行失败: " + e.getMessage());
            log.warn("任务执行失败 {}: {}", task.getId(), e.getMessage());
        } finally {
            task.setFinishedAt(System.currentTimeMillis());
        }
    }

    /**
     * Docker 命令安全执行
     * - 使用 ProcessBuilder 参数列表，不走 shell
     * - 输出大小限制 64KB，防止日志洪泛
     */
    private String executeDocker(String command) throws Exception {
        String[] parts = command.trim().split("\\s+");
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.addAll(Arrays.asList(parts));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = readOutput(process, 64 * 1024); // 输出上限 64KB
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("命令执行超时(30s)");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("退出码: " + exitCode + "\n" + output);
        }
        return output;
    }

    /**
     * HEALTHCHECK 执行（URL 已在 validateTask 阶段通过 SSRF 校验）
     * 响应体截断 500 字符，防止大响应
     */
    private String executeHealthCheck(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        if (body == null) body = "";
        return "HTTP " + response.statusCode() + "\n" + body.substring(0, Math.min(500, body.length()));
    }

    /**
     * 读取进程输出，限制最大读取量（maxBytes），防止大输出 OOM
     */
    private String readOutput(Process process, int maxBytes) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() + line.length() > maxBytes) {
                    sb.append("\n[输出过长，已截断...]");
                    break;
                }
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private void trimHistory() {
        while (taskHistory.size() > MAX_HISTORY) {
            taskHistory.removeLast();
        }
    }
}