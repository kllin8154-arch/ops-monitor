package com.opsmonitor.monitor;

import com.opsmonitor.model.ExporterTemplate;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exporter 部署服务 (9G-5)
 *
 * 支持 3 种部署方式的安装脚本生成：
 * 1. docker  — docker run 命令
 * 2. binary  — 二进制下载 + 直接运行
 * 3. systemd — systemd service 文件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExporterDeployService {

    private final ExporterTemplateRegistry templateRegistry;

    /**
     * N14修复：targetAddress 安全过滤
     *
     * targetAddress 会被拼入 shell 脚本（export KEY="value" 或 docker run -e KEY="value"）。
     * 若不过滤，攻击者可通过注入 `" && rm -rf /` 等字符执行任意命令。
     *
     * 过滤策略：
     * 1. 白名单：只保留 IP/主机名/端口格式允许的字符 [a-zA-Z0-9._:/\-]
     * 2. 长度限制：最大 256 字符
     * 3. null/blank 返回空字符串
     */
    private static final java.util.regex.Pattern TARGET_SAFE_PATTERN =
            java.util.regex.Pattern.compile("[^a-zA-Z0-9._:/\\-]");
    private static final int MAX_TARGET_LENGTH = 256;

    private String sanitizeTargetAddress(String target) {
        if (target == null || target.isBlank()) return "";
        String trimmed = target.trim();
        if (trimmed.length() > MAX_TARGET_LENGTH) {
            trimmed = trimmed.substring(0, MAX_TARGET_LENGTH);
        }
        // 移除所有非白名单字符（shell 元字符、引号、空白等）
        return TARGET_SAFE_PATTERN.matcher(trimmed).replaceAll("");
    }

    /**
     * 生成 Docker 部署命令
     */
    public DeployScript generateDockerScript(String type, int hostPort, String targetAddress) {
        ExporterTemplate tpl = getTemplateOrThrow(type);
        int port = hostPort > 0 ? hostPort : tpl.getMetricsPort();
        String name = tpl.getContainerNamePrefix() + "-" + port;
        // N14修复：过滤 targetAddress 防止 shell 注入
        String safeTarget = sanitizeTargetAddress(targetAddress);
        Map<String, String> envVars = buildEnvVars(type, safeTarget);

        StringBuilder cmd = new StringBuilder("docker run -d");
        cmd.append(" --name ").append(name);
        cmd.append(" --restart unless-stopped");
        cmd.append(" -p ").append(port).append(":").append(tpl.getContainerPort());
        envVars.forEach((k, v) -> cmd.append(" -e ").append(k).append("=\"").append(v).append("\""));
        cmd.append(" ").append(tpl.getImage());

        return DeployScript.builder()
                .type(type).mode("docker").script(cmd.toString())
                .metricsUrl("http://localhost:" + port + "/metrics")
                .build();
    }

    /**
     * 生成 Binary 部署脚本（wget + 直接运行）
     */
    public DeployScript generateBinaryScript(String type, int hostPort, String targetAddress) {
        ExporterTemplate tpl = getTemplateOrThrow(type);
        int port = hostPort > 0 ? hostPort : tpl.getMetricsPort();
        // N14修复：过滤 targetAddress
        String safeTarget = sanitizeTargetAddress(targetAddress);
        Map<String, String> envVars = buildEnvVars(type, safeTarget);

        String binaryUrl = getBinaryDownloadUrl(type);
        String binaryName = type + "_exporter";

        StringBuilder script = new StringBuilder("#!/bin/bash\n");
        script.append("# ").append(tpl.getDisplayName()).append(" 二进制安装脚本\n");
        script.append("set -e\n\n");
        script.append("INSTALL_DIR=/opt/exporters/").append(type).append("\n");
        script.append("mkdir -p $INSTALL_DIR && cd $INSTALL_DIR\n\n");
        script.append("# 下载\n");
        script.append("wget -q ").append(binaryUrl).append(" -O ").append(binaryName).append(".tar.gz\n");
        script.append("tar xzf ").append(binaryName).append(".tar.gz\n");
        script.append("chmod +x ").append(binaryName).append("\n\n");
        script.append("# 启动\n");
        envVars.forEach((k, v) -> script.append("export ").append(k).append("=\"").append(v).append("\"\n"));
        script.append("nohup ./").append(binaryName);
        script.append(" --web.listen-address=:").append(port);
        script.append(" > /var/log/").append(binaryName).append(".log 2>&1 &\n");
        script.append("echo \"").append(binaryName).append(" 已启动，端口: ").append(port).append("\"\n");

        return DeployScript.builder()
                .type(type).mode("binary").script(script.toString())
                .metricsUrl("http://localhost:" + port + "/metrics")
                .build();
    }

    /**
     * 生成 Systemd Service 文件
     */
    public DeployScript generateSystemdScript(String type, int hostPort, String targetAddress) {
        ExporterTemplate tpl = getTemplateOrThrow(type);
        int port = hostPort > 0 ? hostPort : tpl.getMetricsPort();
        // N14修复：过滤 targetAddress
        String safeTarget = sanitizeTargetAddress(targetAddress);
        Map<String, String> envVars = buildEnvVars(type, safeTarget);
        String binaryName = type + "_exporter";

        StringBuilder service = new StringBuilder("[Unit]\n");
        service.append("Description=").append(tpl.getDisplayName()).append("\n");
        service.append("After=network.target\n\n");
        service.append("[Service]\n");
        service.append("Type=simple\n");
        service.append("User=prometheus\n");
        service.append("ExecStart=/opt/exporters/").append(type).append("/").append(binaryName);
        service.append(" --web.listen-address=:").append(port).append("\n");
        envVars.forEach((k, v) -> service.append("Environment=").append(k).append("=").append(v).append("\n"));
        service.append("Restart=always\n");
        service.append("RestartSec=5\n\n");
        service.append("[Install]\n");
        service.append("WantedBy=multi-user.target\n");

        StringBuilder installScript = new StringBuilder("#!/bin/bash\n");
        installScript.append("# 安装 systemd service\n");
        installScript.append("cat > /etc/systemd/system/").append(binaryName).append(".service << 'EOF'\n");
        installScript.append(service);
        installScript.append("EOF\n\n");
        installScript.append("systemctl daemon-reload\n");
        installScript.append("systemctl enable ").append(binaryName).append("\n");
        installScript.append("systemctl start ").append(binaryName).append("\n");
        installScript.append("systemctl status ").append(binaryName).append("\n");

        return DeployScript.builder()
                .type(type).mode("systemd").script(installScript.toString())
                .metricsUrl("http://localhost:" + port + "/metrics")
                .build();
    }

    /**
     * 生成所有类型的 docker run 示例
     */
    public Map<String, String> generateAllDockerExamples() {
        Map<String, String> examples = new LinkedHashMap<>();
        for (ExporterTemplate tpl : templateRegistry.listAll()) {
            String addr = getExampleAddress(tpl.getType());
            DeployScript ds = generateDockerScript(tpl.getType(), tpl.getMetricsPort(), addr);
            examples.put(tpl.getType(), ds.getScript());
        }
        return examples;
    }

    // ==================== 私有方法 ====================

    private ExporterTemplate getTemplateOrThrow(String type) {
        ExporterTemplate tpl = templateRegistry.get(type);
        if (tpl == null) throw new IllegalArgumentException("未知 Exporter 类型: " + type);
        return tpl;
    }

    private Map<String, String> buildEnvVars(String type, String addr) {
        Map<String, String> env = new LinkedHashMap<>();
        if (addr == null || addr.isBlank()) return env;
        switch (type) {
            case "redis" -> env.put("REDIS_ADDR", addr);
            case "mysql" -> env.put("DATA_SOURCE_NAME", addr.contains("@") ? addr : "exporter:exporter@tcp(" + addr + ")/");
            case "postgres", "kingbase", "dm" -> env.put("DATA_SOURCE_NAME", addr.startsWith("postgresql://") ? addr : "postgresql://exporter:exporter@" + addr + "/postgres?sslmode=disable");
            case "oracle" -> env.put("DATA_SOURCE_NAME", addr);
            case "nginx" -> env.put("SCRAPE_URI", addr);
            case "geoserver", "jmx" -> env.put("JMX_URL", addr);
            case "process" -> env.put("PROCFS_PATH", "/host/proc");
        }
        return env;
    }

    private String getBinaryDownloadUrl(String type) {
        return switch (type) {
            case "redis" -> "https://github.com/oliver006/redis_exporter/releases/latest/download/redis_exporter-linux-amd64.tar.gz";
            case "mysql" -> "https://github.com/prometheus/mysqld_exporter/releases/latest/download/mysqld_exporter-linux-amd64.tar.gz";
            case "postgres" -> "https://github.com/prometheus-community/postgres_exporter/releases/latest/download/postgres_exporter-linux-amd64.tar.gz";
            case "nginx" -> "https://github.com/nginxinc/nginx-prometheus-exporter/releases/latest/download/nginx-prometheus-exporter_linux_amd64.tar.gz";
            case "process" -> "https://github.com/ncabatoff/process-exporter/releases/latest/download/process-exporter-linux-amd64.tar.gz";
            default -> "https://github.com/prometheus/node_exporter/releases/latest/download/node_exporter-linux-amd64.tar.gz";
        };
    }

    private String getExampleAddress(String type) {
        return switch (type) {
            case "redis" -> "redis://192.168.1.100:6379";
            case "mysql" -> "192.168.1.100:3306";
            case "postgres", "kingbase", "dm" -> "192.168.1.100:5432";
            case "oracle" -> "oracle://192.168.1.100:1521/orcl";
            case "nginx" -> "http://192.168.1.100/nginx_status";
            case "geoserver", "jmx" -> "service:jmx:rmi:///jndi/rmi://192.168.1.100:1099/jmxrmi";
            default -> "192.168.1.100";
        };
    }

    @Data
    @Builder
    public static class DeployScript {
        private String type;
        private String mode; // docker / binary / systemd
        private String script;
        private String metricsUrl;
    }
}