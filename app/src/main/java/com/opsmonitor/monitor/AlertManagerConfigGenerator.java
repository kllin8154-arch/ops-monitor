package com.opsmonitor.monitor;

import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AlertManager 配置生成器 (10C P0-5)
 *
 * 增强降噪机制：
 * - group_by: [service, alertname, server_name] — 按服务+告警名分组
 * - group_wait: 30s — 等待同组告警合并
 * - group_interval: 5m — 同组间隔
 * - repeat_interval: 4h — 重复告警间隔
 * - inhibit_rules: ExporterDown 抑制同服务器的具体类型告警
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertManagerConfigGenerator {

    private final OpsMonitorProperties properties;

    public void generateConfig() {
        try {
            Path configFile = Paths.get(properties.getCompose().getWorkDir(), "alertmanager.yml");
            String yaml = buildAlertManagerYaml();
            Files.writeString(configFile, yaml, StandardCharsets.UTF_8);
            log.info("[AlertManagerConfig] 已生成增强版 alertmanager.yml (降噪+抑制)");
        } catch (IOException e) {
            log.error("[AlertManagerConfig] 生成失败: {}", e.getMessage());
        }
    }

    private String buildAlertManagerYaml() {
        // v2.10 P2-07:根据配置动态生成 http_config.authorization 块
        // 用简单字符串拼接避免 text block 嵌入的缩进陷阱
        String authBlock = buildAuthorizationBlock();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("global:\n");
        sb.append("  resolve_timeout: 5m\n");
        sb.append("\n");
        sb.append("# 10C P0-5: 路由降噪\n");
        sb.append("route:\n");
        sb.append("  receiver: 'ops-webhook'\n");
        sb.append("  group_by: ['service', 'alertname', 'server_name']\n");
        sb.append("  group_wait: 30s\n");
        sb.append("  group_interval: 5m\n");
        sb.append("  repeat_interval: 4h\n");
        sb.append("\n");
        sb.append("  routes:\n");
        sb.append("    # Agent 级告警:按 agent_id 分组\n");
        sb.append("    - match:\n");
        sb.append("        alertname: AgentDown\n");
        sb.append("      group_by: ['agent_id']\n");
        sb.append("      group_wait: 60s\n");
        sb.append("      repeat_interval: 1h\n");
        sb.append("\n");
        sb.append("    # 平台级告警:紧急\n");
        sb.append("    - match:\n");
        sb.append("        severity: critical\n");
        sb.append("      group_wait: 15s\n");
        sb.append("      repeat_interval: 2h\n");
        sb.append("\n");
        sb.append("receivers:\n");
        sb.append("  - name: 'ops-webhook'\n");
        sb.append("    webhook_configs:\n");
        sb.append("      - url: 'http://host.docker.internal:8080/api/alerts/webhook'\n");
        sb.append("        send_resolved: true\n");
        // v2.10 P2-07:在此追加 http_config 块(若已配置)
        // authBlock 已带正确缩进,直接 append
        sb.append(authBlock);
        sb.append("\n");
        sb.append("# 10C P0-5: 抑制规则\n");
        sb.append("inhibit_rules:\n");
        sb.append("  # ExporterDown 触发时,抑制同服务器的 RedisDown/MySQLDown 等具体告警\n");
        sb.append("  - source_match:\n");
        sb.append("      alertname: ExporterDown\n");
        sb.append("    target_match_re:\n");
        sb.append("      alertname: '.*Down'\n");
        sb.append("    equal: ['server_name']\n");
        sb.append("\n");
        sb.append("  # AgentDown 触发时,抑制该 Agent 上的所有 Exporter 告警\n");
        sb.append("  - source_match:\n");
        sb.append("      alertname: AgentDown\n");
        sb.append("    target_match:\n");
        sb.append("      managed_by: ops-monitor\n");
        sb.append("    equal: ['agent_id']\n");
        return sb.toString();
    }

    /**
     * v2.10 P2-07:根据 ops-monitor.security.webhook-secret 动态生成 http_config 块
     * 未配置时返回空字符串(向后兼容)
     * 已配置时生成 Bearer token 认证块(带正确缩进,直接拼接即可)
     */
    private String buildAuthorizationBlock() {
        String secret = properties.getSecurity() == null
                ? null : properties.getSecurity().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return "";  // 不注入 http_config,保持旧行为
        }
        // YAML 单引号字符串内,单引号本身要写成 '' 转义
        String escaped = secret.replace("'", "''");
        return "        http_config:\n"
                + "          authorization:\n"
                + "            type: Bearer\n"
                + "            credentials: '" + escaped + "'\n";
    }
}