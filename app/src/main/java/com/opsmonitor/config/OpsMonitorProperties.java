package com.opsmonitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 运维监控平台 - 配置属性集中管理
 *
 * P0-3: SecurityConfig 增加 hmacSecret（从配置文件读取，不再硬编码）
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-monitor")
public class OpsMonitorProperties {

    /** v2.22: 系统版本号（影响前端显示，升级时修改此处即可） */
    private String version = "v2.22";

    private DockerConfig docker = new DockerConfig();
    private ComposeConfig compose = new ComposeConfig();
    private PrometheusConfig prometheus = new PrometheusConfig();
    private GrafanaConfig grafana = new GrafanaConfig();
    private SecurityConfig security = new SecurityConfig();
    private VictoriaConfig victoria = new VictoriaConfig();

    @Data
    public static class DockerConfig {
        private String host;
        private int connectTimeout = 5000;
        private int readTimeout = 30000;
    }

    @Data
    public static class ComposeConfig {
        private String workDir;
        private String projectName = "ops-monitor";
    }

    @Data
    public static class PrometheusConfig {
        private int port = 9090;
        private String configPath;
        private int retentionDays = 15;
    }

    @Data
    public static class GrafanaConfig {
        private int port = 3000;
        private String adminUser = "admin";
        /**
         * P1-7 fix: 默认值改为 null，配合 docker-compose.yml 中的 ${OPS_GRAFANA_PASSWORD} 强制用户设置
         * 原 "admin123" 硬编码值在源码泄露场景下直接可用
         */
        private String adminPassword;
        private String dashboardsDir;
    }

    @Data
    public static class SecurityConfig {
        /** 是否启用 API 认证拦截 */
        private boolean enabled = false;
        /** 默认管理员用户名 */
        private String username = "admin";
        /**
         * P1-7 fix: 默认密码改为 null，配合 OPS_ADMIN_PASSWORD 环境变量
         * AuthService.ensureAdminUser() 检测到 null 时会自动生成随机密码并打印 ERROR 日志
         */
        private String password;
        /** P0-3: HMAC 签名密钥（必须配置，否则自动生成随机值） */
        private String hmacSecret;
        /**
         * SEC-4: 可信反向代理 IP（单个 IP）
         * 配置后，仅当请求来自该 IP 时才信任 X-Forwarded-For 头取真实客户端 IP。
         * 未配置时，始终使用 TCP remoteAddr 作为限流 key（更安全的默认行为）。
         * 示例：trusted-proxy: "172.20.0.1"（Nginx 容器 IP）
         */
        private String trustedProxy;

        /**
         * v2.10 P2-07: AlertManager webhook 共享密钥
         * 配置后,webhook 请求必须带 X-Ops-Webhook-Secret header 匹配此值
         * 未配置(null/空) → 向后兼容模式:不校验 header(但日志 WARN 提示)
         * 生产环境强烈建议配置:export OPS_WEBHOOK_SECRET=$(openssl rand -hex 32)
         */
        private String webhookSecret;
    }

    @Data
    public static class VictoriaConfig {
        /**
         * VictoriaMetrics 地址（N9修复：从配置读取，不再硬编码）
         * 默认值适用于本地 Docker Compose 部署
         */
        private String url = "http://127.0.0.1:8428";
    }
}