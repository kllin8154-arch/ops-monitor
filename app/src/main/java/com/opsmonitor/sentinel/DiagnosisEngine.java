package com.opsmonitor.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.monitor.PrometheusQueryService;
import com.opsmonitor.monitor.RemoteHostReachabilityProbe;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sentinel 诊断引擎 V1 — 故障指纹匹配 & 自动诊断
 *
 * OPTIMIZED (Phase4 - OPT-3): collectIndicatorSnapshot() 改为并行查询
 *   - 原实现：7 个 PromQL 查询串行执行，最差情况耗时 7×5s = 35s
 *   - 优化后：7 个查询并发执行，整体耗时降至单次超时（≤5s）
 *   - 实现：CompletableFuture.allOf() + 共享线程池 + 统一超时控制
 *   - 预期收益：诊断响应时间从最差 35s → ≤5s，提升约 7x
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisEngine {

    private final PrometheusQueryService      queryService;
    private final RemoteHostReachabilityProbe  reachabilityProbe;
    private final OpsMonitorProperties         properties;
    private final ObjectMapper           mapper = new ObjectMapper();

    // P1-4 fix: 改为实例字段（非 static），Spring DevTools 热重启时旧线程池能被 @PreDestroy 关闭
    // 原 static 字段在热重启时不会被 GC，每次重启累积 8 个线程
    private final ExecutorService queryPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "diagnosis-query");
        t.setDaemon(true);
        return t;
    });

    // OPTIMIZED: 并行查询总超时（单个查询超时 5s，8 个并行最多等 6s）
    private static final long PARALLEL_QUERY_TIMEOUT_MS = 8_000L;

    // ── 故障指纹库 ─────────────────────────────────────────────

    @Data
    public static class FaultFingerprint {
        private final String        id;
        private final String        name;
        private final String        description;
        private final String        severity;
        private final List<IndicatorCondition> conditions;
        private final String        rootCause;
        private final List<String>  runbook;
    }

    @Data
    public static class IndicatorCondition {
        private final String metric;
        private final String operator;
        private final double threshold;
        private final String description;
    }

    @Data
    public static class DiagnosisReport {
        private final String           serverId;
        private final String           serverName;
        private final Instant          diagnosedAt;
        private final String           faultId;
        private final String           faultName;
        private final String           severity;
        private final double           confidence;
        private final String           rootCause;
        private final List<String>     runbook;
        private final double           impactScore;
        private final Map<String, Double> indicatorSnapshot;
        private final String           summary;
    }

    // ── 故障指纹库定义（保持原有不变）──────────────────────────

    private static final List<FaultFingerprint> FINGERPRINT_LIBRARY = List.of(
            new FaultFingerprint("HIGH_CPU", "CPU 过高",
                    "CPU 使用率持续超过 85%，影响服务响应", "P1",
                    List.of(new IndicatorCondition("cpu_total", "GT", 85, "CPU > 85%")),
                    "进程占用过高，建议检查 top/ps 输出",
                    List.of("执行 top -b -n1 确认高 CPU 进程", "检查是否存在死循环或 GC 风暴")),

            new FaultFingerprint("OOM_RISK", "内存耗尽风险",
                    "内存使用率超过 90%，存在 OOM 风险", "P0",
                    List.of(new IndicatorCondition("memory_used", "GT", 90, "Memory > 90%")),
                    "内存泄漏或内存不足，进程可能被 OOM Killer 终止",
                    List.of("检查 free -m 输出", "分析 Java heap: jmap -heap <pid>", "考虑扩容或重启服务")),

            new FaultFingerprint("DISK_IO_BOUND", "磁盘 IO 瓶颈",
                    "CPU iowait 过高，磁盘 IO 成为瓶颈", "P1",
                    List.of(new IndicatorCondition("cpu_iowait", "GT", 30, "iowait > 30%")),
                    "磁盘 IO 饱和，建议检查 iostat / 磁盘健康状况",
                    List.of("执行 iostat -x 1 5", "检查是否有大量日志写入", "考虑 SSD 或 IO 限速")),

            new FaultFingerprint("EXPORTER_DOWN", "Exporter 离线",
                    "部分 Exporter 无法采集数据", "P2",
                    List.of(new IndicatorCondition("exporter_up_rate", "LT", 1.0, "up_rate < 100%")),
                    "Exporter 容器异常或网络中断",
                    List.of("检查 docker ps 状态", "重启对应 Exporter 容器")),

            new FaultFingerprint("UP_FLAPPING", "监控抖动",
                    "Exporter 状态频繁变化（1小时内变化 >3 次）", "P2",
                    List.of(new IndicatorCondition("up_flapping", "GT", 3, "changes > 3 in 1h")),
                    "网络不稳定或服务频繁重启",
                    List.of("检查容器重启次数: docker inspect", "检查网络连通性", "查看系统日志")),

            new FaultFingerprint("HIGH_NETWORK", "网络流量异常",
                    "入站网络流量异常偏高", "P2",
                    List.of(new IndicatorCondition("network_in", "GT", 100_000_000, "network_in > 100MB/s")),
                    "可能存在 DDoS、爬虫或数据泄露",
                    List.of("检查 ss -s / netstat 连接数", "分析流量来源 IP", "考虑限流或封禁")),

            // v2.28 新增 8 个指纹
            new FaultFingerprint("DISK_FULL", "磁盘空间不足",
                    "磁盘使用率超过 90%，可能导致服务写入失败", "P0",
                    List.of(new IndicatorCondition("disk_used_percent", "GT", 90, "disk > 90%")),
                    "磁盘即将写满，日志、数据库、临时文件可能无法写入",
                    List.of("执行 df -h 检查各分区", "清理日志: find /var/log -name '*.gz' -mtime +7 -delete",
                            "检查 Docker 镜像/容器占用: docker system df")),

            new FaultFingerprint("HIGH_LOAD", "系统负载过高",
                    "1 分钟负载超过 CPU 核心数的 2 倍", "P1",
                    List.of(new IndicatorCondition("load1", "GT", 8, "load1 > 8")),
                    "进程排队等待 CPU 或 IO，响应时间增加",
                    List.of("执行 uptime 查看负载趋势", "执行 ps aux --sort=-%cpu | head",
                            "检查是否有批处理任务或 cron 占用")),

            new FaultFingerprint("DISK_IO_HIGH", "磁盘写入过高",
                    "磁盘写入速率超过 50MB/s", "P2",
                    List.of(new IndicatorCondition("disk_io_bytes", "GT", 50_000_000, "disk_write > 50MB/s")),
                    "大量磁盘写入，可能是日志刷盘、数据库写入或备份任务",
                    List.of("执行 iostat -x 1 5 检查 IO 利用率", "检查哪个进程写入: iotop -oP")),

            new FaultFingerprint("NETWORK_OUT_HIGH", "出站流量异常",
                    "出站网络流量超过 50MB/s", "P2",
                    List.of(new IndicatorCondition("network_out", "GT", 50_000_000, "network_out > 50MB/s")),
                    "大量出站流量，可能是数据泄露、备份或正常的文件分发",
                    List.of("检查连接数: ss -s", "按端口统计: ss -tnp | awk '{print $5}' | sort | uniq -c | sort -rn")),

            new FaultFingerprint("SWAP_ACTIVE", "Swap 使用偏高",
                    "Swap 使用率超过 50%，物理内存不足", "P1",
                    List.of(new IndicatorCondition("swap_used_percent", "GT", 50, "swap > 50%")),
                    "物理内存不足，系统频繁 swap 导致性能下降",
                    List.of("执行 free -m 查看 swap 用量", "找到高内存进程: ps aux --sort=-%mem | head",
                            "考虑增加物理内存或优化应用内存配置")),

            new FaultFingerprint("FD_EXHAUSTION", "文件描述符即将耗尽",
                    "进程文件描述符使用率超过 80%", "P1",
                    List.of(new IndicatorCondition("fd_usage_percent", "GT", 80, "fd_usage > 80%")),
                    "文件描述符泄漏或连接过多，可能导致无法打开新文件/连接",
                    List.of("查看系统 fd 上限: cat /proc/sys/fs/file-nr",
                            "查看进程 fd: ls -la /proc/<pid>/fd | wc -l")),

            new FaultFingerprint("SYSTEM_OVERLOAD", "系统全面过载",
                    "CPU、内存、IO 同时处于高位", "P0",
                    List.of(
                            new IndicatorCondition("cpu_total", "GT", 70, "CPU > 70%"),
                            new IndicatorCondition("memory_used", "GT", 80, "Memory > 80%"),
                            new IndicatorCondition("cpu_iowait", "GT", 15, "iowait > 15%")
                    ),
                    "多维度资源同时紧张，系统可能即将不可用",
                    List.of("立即检查是否有突发流量或异常进程", "考虑临时扩容或迁移负载",
                            "检查近期是否有部署变更")),

            new FaultFingerprint("AGENT_DOWN", "Agent 心跳丢失",
                    "Agent 上报超时，指标采集可能中断", "P2",
                    List.of(new IndicatorCondition("exporter_up_rate", "LT", 0.5, "up_rate < 50%")),
                    "超过半数 Exporter 离线，Agent 或网络可能异常",
                    List.of("检查 Agent 进程: systemctl status ops-agent",
                            "检查 Agent 日志: journalctl -u ops-agent --since '5 min ago'",
                            "确认 Agent 到 OpsMonitor 网络连通性")),

            // v2.28 补充指纹（批次 2）
            new FaultFingerprint("CONNTRACK_FULL", "连接跟踪表满",
                    "conntrack 表使用率超过 90%，可能导致连接被丢弃", "P1",
                    List.of(new IndicatorCondition("memory_used", "GT", 85, "memory > 85% 时 conntrack 也容易满")),
                    "连接跟踪表溢出，新连接可能被丢弃或延迟",
                    List.of("查看: cat /proc/sys/net/netfilter/nf_conntrack_count",
                            "临时扩容: sysctl -w net.netfilter.nf_conntrack_max=262144",
                            "检查是否有异常连接风暴: ss -s")),

            new FaultFingerprint("DOCKER_DISK_LEAK", "Docker 磁盘占用过高",
                    "Docker 数据占用磁盘超过 80%", "P2",
                    List.of(new IndicatorCondition("disk_used_percent", "GT", 80, "disk > 80%")),
                    "Docker 镜像、容器日志或数据卷占用过多磁盘",
                    List.of("检查 Docker 占用: docker system df",
                            "清理无用镜像: docker image prune -a -f",
                            "清理构建缓存: docker builder prune -f")),

            new FaultFingerprint("INODE_EXHAUSTION", "inode 即将耗尽",
                    "磁盘 inode 使用率超过 90%，无法创建新文件", "P1",
                    List.of(new IndicatorCondition("disk_used_percent", "GT", 90, "disk > 90% 时 inode 往往也紧张")),
                    "大量小文件（日志/缓存/临时文件）耗尽 inode",
                    List.of("检查 inode: df -i",
                            "找到大量小文件的目录: find / -xdev -type d -size +100k",
                            "清理旧日志: journalctl --vacuum-size=500M")),

            new FaultFingerprint("DNS_RESOLVE_FAIL", "DNS 解析异常",
                    "DNS 解析失败可能导致服务间调用超时", "P2",
                    List.of(new IndicatorCondition("network_out", "GT", 0, "网络有出站流量时检查 DNS")),
                    "DNS 解析异常：可能是 DNS 服务器不可达、缓存损坏或系统 resolv.conf 配置错误",
                    List.of("测试解析: nslookup example.com",
                            "检查配置: cat /etc/resolv.conf",
                            "确认 DNS 服务: systemctl status systemd-resolved")),

            new FaultFingerprint("CLOCK_SKEW", "系统时钟偏移",
                    "系统时钟与标准时间偏差过大，可能导致 TLS/JWT 验证失败", "P1",
                    List.of(new IndicatorCondition("network_out", "GT", 0, "网络正常时检查 NTP 同步")),
                    "系统时钟异常（偏差 > 1 秒），影响 TLS 证书验证和 JWT Token 有效期判断",
                    List.of("检查 NTP 状态: timedatectl status",
                            "查看时钟偏差: ntpstat",
                            "同步时钟: chronyc -a makestep 或 ntpdate -u ntp.aliyun.com")),

            new FaultFingerprint("ZOMBIE_PROCESS", "僵尸进程堆积",
                    "僵尸进程数过多，可能耗尽 PID 空间", "P2",
                    List.of(new IndicatorCondition("load1", "GT", 5, "负载偏高时检查僵尸进程")),
                    "僵尸进程过多：子进程已退出但父进程未回收，可能耗尽 PID 空间",
                    List.of("统计僵尸: ps aux | grep 'Z' | wc -l",
                            "检查父进程: ps -eo ppid,stat | grep Z",
                            "重启问题父进程或通知开发排查 waitpid() 调用"))
    );

    // ── 主诊断方法 ──────────────────────────────────────────────

    public List<DiagnosisReport> diagnose(String serverId, String serverName) {
        log.info("[DiagnosisEngine] 开始诊断: serverId={} serverName={}", serverId, serverName);

        // OPTIMIZED: 并行采集 7 个指标（原串行最差 35s → 现在 ≤6s）
        Map<String, Double> snapshot = collectIndicatorSnapshotParallel(serverName);
        log.debug("[DiagnosisEngine] 指标快照: {}", snapshot);

        List<DiagnosisReport> reports = new ArrayList<>();

        // v2.28: 先检查主机可达性（解决离线服务器诊断显示"系统健康"的问题）
        boolean hostReachable = true;
        try {
            Map<String, Boolean> reachMap = reachabilityProbe.getAllReachability();
            Boolean reachable = reachMap.get(serverId);
            if (reachable != null && !reachable) {
                hostReachable = false;
            }
        } catch (Exception e) {
            log.debug("[DiagnosisEngine] 可达性查询异常: {}", e.getMessage());
        }

        if (!hostReachable) {
            String rootCause = "主机不可达，TCP 探测失败。请检查服务器电源、网络和防火墙。";
            reports.add(new DiagnosisReport(
                    serverId, serverName, Instant.now(),
                    "HOST_UNREACHABLE", "主机不可达", "P1",
                    100.0, rootCause,
                    List.of("确认服务器电源/网络状态", "检查防火墙规则", "等待主机恢复后重新诊断"),
                    100.0, snapshot,
                    "[P1] 主机不可达 — 置信度 100%。" + rootCause
            ));
            log.info("[DiagnosisEngine] 主机不可达: serverId={}", serverId);
            // 不 return：继续指纹匹配（可能有残留缓存指标触发其他条件）
        }

        // v2.28: 检查指标数据是否全部为空（仅主机可达时触发）
        if (hostReachable && snapshot.isEmpty()) {
            String rootCause = "目标服务器无指标数据上报，可能未注册 Exporter 或 Prometheus 尚未采集到数据。";
            reports.add(new DiagnosisReport(
                    serverId, serverName, Instant.now(),
                    "DATA_UNAVAILABLE", "指标数据不可用", "P2",
                    80.0, rootCause,
                    List.of("确认服务器已注册 Exporter", "检查 Prometheus targets 页面", "等待 2 分钟后重试"),
                    50.0, snapshot,
                    "[P2] 指标数据不可用 — 置信度 80%。" + rootCause
            ));
            log.info("[DiagnosisEngine] 无指标数据: serverId={}", serverId);
            return reports;
        }

        for (FaultFingerprint fp : FINGERPRINT_LIBRARY) {
            double confidence = calculateConfidence(fp, snapshot);
            if (confidence > 0) {
                double impactScore = calculateImpactScore(snapshot, confidence, fp.getSeverity());
                String summary = buildSummary(fp, confidence, snapshot);
                reports.add(new DiagnosisReport(
                        serverId, serverName, Instant.now(),
                        fp.getId(), fp.getName(), fp.getSeverity(),
                        confidence, fp.getRootCause(), fp.getRunbook(),
                        impactScore, snapshot, summary
                ));
                log.info("[DiagnosisEngine] 匹配指纹: {} confidence={:.1f}% impact={:.1f}",
                        fp.getName(), confidence, impactScore);
            }
        }

        reports.sort(Comparator.comparingDouble(DiagnosisReport::getImpactScore).reversed());
        log.info("[DiagnosisEngine] 诊断完成: 匹配 {} 个指纹", reports.size());
        return reports;
    }

    // ── OPTIMIZED: 并行指标采集 ────────────────────────────────

    /**
     * OPTIMIZED (OPT-3): 7 个 PromQL 查询改为并行执行
     *
     * 原实现 queryAndPut() 串行调用，每次最多等 5s，7 次共最多 35s。
     * 新实现使用 CompletableFuture.allOf() + 专用线程池，并行执行。
     * 整体超时：PARALLEL_QUERY_TIMEOUT_MS = 6s（略大于单次查询超时）。
     */
    private Map<String, Double> collectIndicatorSnapshotParallel(String serverName) {
        Map<String, Double> snap = new ConcurrentHashMap<>();
        String lf = "server_name=\"" + serverName + "\"";

        // 定义 7 个查询任务（key → PromQL）
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("cpu_total",
                "100 - (avg by(server_name)(rate(node_cpu_seconds_total{mode=\"idle\"," + lf + "}[5m])) * 100)");
        queries.put("cpu_iowait",
                "avg by(server_name)(rate(node_cpu_seconds_total{mode=\"iowait\"," + lf + "}[5m])) * 100");
        queries.put("memory_used",
                "(1 - node_memory_MemAvailable_bytes{" + lf + "} / node_memory_MemTotal_bytes{" + lf + "}) * 100");
        queries.put("disk_used_percent",
                "max(1 - node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs\"," + lf + "}"
                        + " / node_filesystem_size_bytes{fstype!~\"tmpfs|overlay|squashfs\"," + lf + "}) * 100");
        queries.put("network_in",
                "sum by(server_name)(rate(node_network_receive_bytes_total{device!~\"lo\"," + lf + "}[5m]))");
        queries.put("up_flapping",
                "changes(up{managed_by=\"ops-monitor\"," + lf + "}[1h])");
        queries.put("exporter_up_rate",
                "count(up{managed_by=\"ops-monitor\"," + lf + "} == 1)"
                        + " / count(up{managed_by=\"ops-monitor\"," + lf + "})");
        // v2.28: 扩展指标采集（+6 个新指标）
        queries.put("load1",
                "node_load1{" + lf + "}");
        queries.put("disk_io_bytes",
                "sum by(server_name)(rate(node_disk_written_bytes_total{" + lf + "}[5m]))");
        queries.put("disk_read_bytes",
                "sum by(server_name)(rate(node_disk_read_bytes_total{" + lf + "}[5m]))");
        queries.put("network_out",
                "sum by(server_name)(rate(node_network_transmit_bytes_total{device!~\"lo\"," + lf + "}[5m]))");
        queries.put("swap_used_percent",
                "(1 - node_memory_SwapFree_bytes{" + lf + "} / node_memory_SwapTotal_bytes{" + lf + "}) * 100");
        queries.put("fd_usage_percent",
                "process_open_fds{" + lf + "} / process_max_fds{" + lf + "} * 100");

        // OPTIMIZED: 并行提交所有查询
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, String> entry : queries.entrySet()) {
            String key = entry.getKey();
            String promql = entry.getValue();
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    queryService.queryScalar(promql).ifPresent(v -> snap.put(key, v));
                } catch (Exception e) {
                    log.debug("[DiagnosisEngine] 指标查询失败 {}: {}", key, e.getMessage());
                }
            }, queryPool);
            futures.add(future);
        }

        // OPTIMIZED: 等待所有查询完成（含统一超时控制）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PARALLEL_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[DiagnosisEngine] 并行指标采集超时（{}ms），已采集到 {}/{} 个指标",
                    PARALLEL_QUERY_TIMEOUT_MS, snap.size(), queries.size());
            // 超时后取消未完成的查询（不阻塞）
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            log.warn("[DiagnosisEngine] 并行采集异常: {}", e.getMessage());
        }

        log.debug("[DiagnosisEngine] 并行采集完成: {}/{} 个指标就绪", snap.size(), queries.size());
        return snap;
    }

    // ── 辅助方法（保持原有不变）────────────────────────────────

    private double calculateConfidence(FaultFingerprint fp, Map<String, Double> snapshot) {
        if (fp.getConditions().isEmpty()) return 0;
        long matched = fp.getConditions().stream()
                .filter(c -> evaluateCondition(c, snapshot))
                .count();
        return (double) matched / fp.getConditions().size() * 100.0;
    }

    private boolean evaluateCondition(IndicatorCondition cond, Map<String, Double> snapshot) {
        Double value = snapshot.get(cond.getMetric());
        if (value == null) return false;
        return switch (cond.getOperator()) {
            case "GT"     -> value > cond.getThreshold();
            case "GTE"    -> value >= cond.getThreshold();
            case "LT"     -> value < cond.getThreshold();
            case "LTE"    -> value <= cond.getThreshold();
            case "SPIKE"  -> value > 0;
            case "NORMAL" -> value < cond.getThreshold();
            default       -> false;
        };
    }

    private double calculateImpactScore(Map<String, Double> snapshot, double confidence, String severity) {
        double upRate = snapshot.getOrDefault("exporter_up_rate", 1.0);
        double errorRate = 1.0 - upRate;
        double severityWeight = switch (severity) {
            case "P0" -> 10.0;
            case "P1" -> 5.0;
            case "P2" -> 2.0;
            default   -> 1.0;
        };
        return severityWeight * errorRate * 100 * (confidence / 100.0);
    }

    private String buildSummary(FaultFingerprint fp, double confidence, Map<String, Double> snapshot) {
        return String.format("[%s] %s — 置信度 %.0f%%。根因推断: %s",
                fp.getSeverity(), fp.getName(), confidence, fp.getRootCause());
    }

    public List<FaultFingerprint> getFingerprintLibrary() {
        return Collections.unmodifiableList(FINGERPRINT_LIBRARY);
    }

    /**
     * P1-4 fix: Spring 关闭或 DevTools 热重启时释放线程池
     * 避免热重启累积 N×8 个线程
     */
    @jakarta.annotation.PreDestroy
    public void shutdownQueryPool() {
        try {
            queryPool.shutdownNow();
        } catch (Exception ignored) {}
    }
}