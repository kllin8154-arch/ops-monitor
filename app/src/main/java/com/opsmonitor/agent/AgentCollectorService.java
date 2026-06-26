package com.opsmonitor.agent;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.*;

/**
 * Agent 本地指标采集服务 (10A-4)
 *
 * 采集本机系统指标，输出 Prometheus 格式。
 * 不依赖 node_exporter，直接通过 JVM API 采集。
 *
 * 采集指标：
 * - agent_cpu_usage_percent
 * - agent_memory_used_bytes / agent_memory_total_bytes
 * - agent_disk_used_bytes / agent_disk_total_bytes
 * - agent_jvm_memory_used_bytes
 * - agent_uptime_seconds
 */
@Slf4j
@Service
public class AgentCollectorService {

    private final OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
    private final long startTime = System.currentTimeMillis();

    /**
     * 采集所有指标，返回 Prometheus text format 行
     */
    public List<MetricSample> collectAll(String agentId, Map<String, String> baseLabels) {
        List<MetricSample> samples = new ArrayList<>();
        long ts = System.currentTimeMillis();

        Map<String, String> labels = new LinkedHashMap<>(baseLabels);
        labels.put("agent_id", agentId);

        // CPU — 审查修复：优先使用 getCpuLoad()（真实CPU使用率 0-1），
        // fallback 到 getSystemLoadAverage()/cores（负载均值，不等于使用率）
        int cpuCores = osMxBean.getAvailableProcessors();
        double cpuUsage = -1;

        if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double cpuLoad = sunOs.getCpuLoad(); // Java 14+，返回 [0.0, 1.0]，-1 表示不可用
            if (cpuLoad >= 0) {
                cpuUsage = cpuLoad * 100.0;
            }
        }

        // fallback：loadAverage / cores（仅在 getCpuLoad 不可用时使用，精度较低）
        if (cpuUsage < 0) {
            double loadAvg = osMxBean.getSystemLoadAverage();
            if (loadAvg >= 0) {
                cpuUsage = Math.min(loadAvg / cpuCores * 100.0, 100.0);
            }
        }

        if (cpuUsage >= 0) {
            samples.add(MetricSample.builder()
                    .name("agent_cpu_usage_percent").value(cpuUsage).labels(labels).timestamp(ts).build());
        }
        samples.add(MetricSample.builder()
                .name("agent_cpu_cores").value(cpuCores).labels(labels).timestamp(ts).build());

        // Memory (JVM Runtime)
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long usedMem = totalMem - freeMem;
        samples.add(MetricSample.builder()
                .name("agent_jvm_memory_used_bytes").value(usedMem).labels(labels).timestamp(ts).build());
        samples.add(MetricSample.builder()
                .name("agent_jvm_memory_total_bytes").value(totalMem).labels(labels).timestamp(ts).build());

        // OS Memory (如果支持)
        if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            long osTotalMem = sunOs.getTotalMemorySize();
            long osFreeMemory = sunOs.getFreeMemorySize();
            samples.add(MetricSample.builder()
                    .name("agent_memory_total_bytes").value(osTotalMem).labels(labels).timestamp(ts).build());
            samples.add(MetricSample.builder()
                    .name("agent_memory_used_bytes").value(osTotalMem - osFreeMemory).labels(labels).timestamp(ts).build());
        }

        // Disk
        try {
            for (FileStore store : FileSystems.getDefault().getFileStores()) {
                String type = store.type();
                if (type.contains("tmpfs") || type.contains("overlay") || type.contains("proc")) continue;
                long totalSpace = store.getTotalSpace();
                long usableSpace = store.getUsableSpace();
                if (totalSpace <= 0) continue;

                Map<String, String> diskLabels = new LinkedHashMap<>(labels);
                diskLabels.put("mountpoint", store.toString().split(" ")[0]);
                samples.add(MetricSample.builder()
                        .name("agent_disk_total_bytes").value(totalSpace).labels(diskLabels).timestamp(ts).build());
                samples.add(MetricSample.builder()
                        .name("agent_disk_used_bytes").value(totalSpace - usableSpace).labels(diskLabels).timestamp(ts).build());
            }
        } catch (IOException e) {
            log.debug("采集磁盘指标异常: {}", e.getMessage());
        }

        // Uptime
        double uptime = (System.currentTimeMillis() - startTime) / 1000.0;
        samples.add(MetricSample.builder()
                .name("agent_uptime_seconds").value(uptime).labels(labels).timestamp(ts).build());

        return samples;
    }

    /**
     * 转为 Prometheus text exposition format
     *
     * N7 修复：label 值转义（遵循 Prometheus text format 规范）
     * 规范要求 label value 中以下字符必须转义：
     *   \  →  \\
     *   "  →  \"
     *   \n →  \n（字面量反斜杠+n）
     */
    public String toPrometheusTextFormat(List<MetricSample> samples) {
        StringBuilder sb = new StringBuilder();
        for (MetricSample s : samples) {
            sb.append(s.getName());
            if (s.getLabels() != null && !s.getLabels().isEmpty()) {
                sb.append('{');
                StringJoiner sj = new StringJoiner(",");
                s.getLabels().forEach((k, v) -> sj.add(k + "=\"" + escapeLabelValue(v) + "\""));
                sb.append(sj);
                sb.append('}');
            }
            sb.append(' ').append(s.getValue());
            if (s.getTimestamp() > 0) {
                sb.append(' ').append(s.getTimestamp());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 转义 Prometheus label value 中的特殊字符
     * 顺序：先转义 \，再转义 "，再转义换行
     */
    private String escapeLabelValue(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")   // \ → \\
                .replace("\"", "\\\"")   // " → \"
                .replace("\n", "\\n")    // 换行 → \n
                .replace("\r", "\\r");   // 回车 → \r
    }

    @Data
    @Builder
    public static class MetricSample {
        private String name;
        private double value;
        private Map<String, String> labels;
        private long timestamp;
    }
}