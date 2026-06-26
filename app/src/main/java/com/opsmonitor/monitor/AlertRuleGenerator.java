package com.opsmonitor.monitor;

import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.ExporterInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 告警规则自动生成器 V2 (10B-7)
 *
 * 3 类告警：
 * 1. 基础设施告警: HighCPU / HighMemory / HighDisk
 * 2. Exporter 告警: ExporterDown + 按类型动态生成 + ExporterFlapping
 * 3. 平台级告警(10B): AgentDown / RemoteWriteFail
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertRuleGenerator {

    private final OpsMonitorProperties properties;
    private final ExporterManager exporterManager;

    public void generateAlertRules() {
        try {
            Path rulesFile = Paths.get(properties.getCompose().getWorkDir(), "alert.rules.yml");
            Set<String> activeTypes = exporterManager.listExporters().stream()
                    .map(ExporterInstance::getType).collect(Collectors.toSet());
            String yaml = buildYaml(activeTypes);
            Files.writeString(rulesFile, yaml, StandardCharsets.UTF_8);
            log.info("[AlertRuleGenerator V2] 已生成告警规则 (活跃类型: {})", activeTypes);
        } catch (IOException e) {
            log.error("[AlertRuleGenerator V2] 生成失败: {}", e.getMessage());
        }
    }

    private String buildYaml(Set<String> activeTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("groups:\n");

        // ========== Group 1: 基础设施 ==========
        sb.append("  - name: infrastructure\n");
        sb.append("    rules:\n");
        sb.append(rule("HighCPU", "100-(avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))*100)>80", "2m", "warning",
                "CPU 使用率过高 ({{ $labels.instance }})", "CPU > 80%, 当前: {{ $value }}%"));
        sb.append(rule("HighMemory", "(1-node_memory_MemAvailable_bytes/node_memory_MemTotal_bytes)*100>85", "2m", "warning",
                "内存使用率过高 ({{ $labels.instance }})", "内存 > 85%, 当前: {{ $value }}%"));
        sb.append(rule("HighDisk", "(1-node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\"}/node_filesystem_size_bytes{fstype!~\"tmpfs|overlay\"})*100>90", "5m", "critical",
                "磁盘使用率过高 ({{ $labels.instance }})", "磁盘 > 90%, 当前: {{ $value }}%"));

        // ========== Group 2: Exporter ==========
        sb.append("  - name: exporters\n");
        sb.append("    rules:\n");
        sb.append(rule("ExporterDown", "up{managed_by=\"ops-monitor\"}==0", "1m", "critical",
                "Exporter 离线 ({{ $labels.exporter_type }})", "{{ $labels.server_name }} 的 {{ $labels.exporter_type }} 已离线"));

        // 动态类型规则
        Map<String, String> typeNames = Map.of(
                "redis","Redis", "mysql","MySQL", "nginx","Nginx", "postgres","PostgreSQL",
                "oracle","Oracle", "dm","DM", "kingbase","Kingbase", "geoserver","GeoServer", "jmx","JMX");
        for (var e : typeNames.entrySet()) {
            if (activeTypes.contains(e.getKey())) {
                sb.append(rule(e.getValue() + "Down",
                        "up{exporter_type=\"" + e.getKey() + "\",managed_by=\"ops-monitor\"}==0", "1m", "critical",
                        e.getValue() + " 离线 ({{ $labels.server_name }})",
                        "{{ $labels.server_name }} " + e.getValue() + " Exporter 离线 1 分钟"));
            }
        }

        // Exporter Flapping（5分钟内状态变化 >3 次）
        sb.append(rule("ExporterFlapping",
                "changes(up{managed_by=\"ops-monitor\"}[5m])>3", "1m", "warning",
                "Exporter 状态抖动 ({{ $labels.exporter_type }})",
                "{{ $labels.server_name }} 的 {{ $labels.exporter_type }} 5分钟内状态变化 {{ $value }} 次"));

        // ========== Group 3: 平台级 (10B) ==========
        sb.append("  - name: platform\n");
        sb.append("    rules:\n");
        sb.append(rule("AgentDown",
                "agent_uptime_seconds==0 or absent(agent_uptime_seconds)", "2m", "critical",
                "Agent 离线 ({{ $labels.agent_id }})", "Agent {{ $labels.agent_id }} 已离线超过 2 分钟"));
        sb.append(rule("RemoteWriteFail",
                "increase(agent_remote_write_failures_total[5m])>5", "1m", "warning",
                "Remote Write 失败 ({{ $labels.agent_id }})", "Agent {{ $labels.agent_id }} 5分钟内写入失败 {{ $value }} 次"));

        return sb.toString();
    }

    private String rule(String name, String expr, String dur, String sev, String summary, String desc) {
        return String.format("""
                      - alert: %s
                        expr: %s
                        for: %s
                        labels:
                          severity: %s
                        annotations:
                          summary: "%s"
                          description: "%s"
                """, name, expr, dur, sev, summary, desc);
    }
}