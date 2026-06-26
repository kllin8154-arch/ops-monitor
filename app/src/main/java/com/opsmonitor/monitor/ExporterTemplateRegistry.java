package com.opsmonitor.monitor;

import com.opsmonitor.model.ExporterTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 内置 Exporter 模板注册表
 *
 * 根据 PRODUCT_SPEC.md 定义，包含以下 Exporter：
 * - 数据库: Redis / MySQL / PostgreSQL / Oracle / DM(达梦) / Kingbase(人大金仓)
 * - 中间件: Nginx / GeoServer(JMX)
 * - 系统:   node_exporter / cAdvisor（已在 docker-compose 中内置）
 */
@Component
public class ExporterTemplateRegistry {

    private final Map<String, ExporterTemplate> templates = new LinkedHashMap<>();

    public ExporterTemplateRegistry() {
        // ===== 数据库 Exporter =====
        register(ExporterTemplate.builder()
                .type("redis")
                .displayName("Redis Exporter")
                .image("oliver006/redis_exporter:latest")
                .containerNamePrefix("ops-redis-exporter")
                .metricsPort(9121)
                .containerPort(9121)
                .jobName("redis")
                .defaultEnv(Map.of())
                .command(List.of())
                .requiredParams(List.of("REDIS_ADDR"))
                .scrapeTarget("ops-redis-exporter:9121")
                .category("database")
                .build());

        register(ExporterTemplate.builder()
                .type("mysql")
                .displayName("MySQL Exporter")
                .image("prom/mysqld-exporter:latest")
                .containerNamePrefix("ops-mysql-exporter")
                .metricsPort(9104)
                .containerPort(9104)
                .jobName("mysql")
                .defaultEnv(Map.of())
                .command(List.of())
                .requiredParams(List.of("DATA_SOURCE_NAME"))
                .scrapeTarget("ops-mysql-exporter:9104")
                .category("database")
                .build());

        register(ExporterTemplate.builder()
                .type("postgres")
                .displayName("PostgreSQL Exporter")
                .image("prometheuscommunity/postgres-exporter:latest")
                .containerNamePrefix("ops-postgres-exporter")
                .metricsPort(9187)
                .containerPort(9187)
                .jobName("postgres")
                .defaultEnv(Map.of())
                .command(List.of())
                .requiredParams(List.of("DATA_SOURCE_NAME"))
                .scrapeTarget("ops-postgres-exporter:9187")
                .category("database")
                .build());

        register(ExporterTemplate.builder()
                .type("oracle")
                .displayName("Oracle Exporter")
                .image("iamseth/oracledb_exporter:latest")
                .containerNamePrefix("ops-oracle-exporter")
                .metricsPort(9161)
                .containerPort(9161)
                .jobName("oracle")
                .defaultEnv(Map.of())
                .command(List.of())
                .requiredParams(List.of("DATA_SOURCE_NAME"))
                .scrapeTarget("ops-oracle-exporter:9161")
                .category("database")
                .build());

        register(ExporterTemplate.builder()
                .type("kingbase")
                .displayName("Kingbase Exporter (基于 postgres_exporter)")
                .image("prometheuscommunity/postgres-exporter:latest")
                .containerNamePrefix("ops-kingbase-exporter")
                .metricsPort(9188)
                .containerPort(9187)
                .jobName("kingbase")
                .defaultEnv(Map.of())
                .command(List.of())
                .requiredParams(List.of("DATA_SOURCE_NAME"))
                .scrapeTarget("ops-kingbase-exporter:9187")
                .category("database")
                .build());

        // ===== 中间件 Exporter =====
        register(ExporterTemplate.builder()
                .type("nginx")
                .displayName("Nginx Exporter")
                .image("nginx/nginx-prometheus-exporter:latest")
                .containerNamePrefix("ops-nginx-exporter")
                .metricsPort(9113)
                .containerPort(9113)
                .jobName("nginx")
                .defaultEnv(Map.of())
                .command(List.of())
                .requiredParams(List.of("SCRAPE_URI"))
                .scrapeTarget("ops-nginx-exporter:9113")
                .category("middleware")
                .build());

        register(ExporterTemplate.builder()
                .type("geoserver")
                .displayName("GeoServer JMX Exporter")
                .image("bitnami/jmx-exporter:latest")
                .containerNamePrefix("ops-geoserver-exporter")
                .metricsPort(9404)
                .containerPort(9404)
                .jobName("geoserver")
                .defaultEnv(Map.of())
                .command(List.of())
                .requiredParams(List.of("JMX_URL"))
                .scrapeTarget("ops-geoserver-exporter:9404")
                .category("middleware")
                .build());

        // ===== 国产数据库 =====
        register(ExporterTemplate.builder()
                .type("dm").displayName("DM(达梦) Exporter")
                .image("prometheuscommunity/postgres-exporter:latest")
                .containerNamePrefix("ops-dm-exporter")
                .metricsPort(9189).containerPort(9187).jobName("dm")
                .defaultEnv(Map.of()).command(List.of())
                .requiredParams(List.of("DATA_SOURCE_NAME"))
                .scrapeTarget("ops-dm-exporter:9187").category("database").build());

        // ===== JVM =====
        register(ExporterTemplate.builder()
                .type("jmx").displayName("JMX Exporter")
                .image("bitnami/jmx-exporter:latest")
                .containerNamePrefix("ops-jmx-exporter")
                .metricsPort(9404).containerPort(9404).jobName("jmx")
                .defaultEnv(Map.of()).command(List.of())
                .requiredParams(List.of("JMX_URL"))
                .scrapeTarget("ops-jmx-exporter:9404").category("jvm").build());

        // ===== 系统 =====
        register(ExporterTemplate.builder()
                .type("process").displayName("Process Exporter")
                .image("ncabatoff/process-exporter:latest")
                .containerNamePrefix("ops-process-exporter")
                .metricsPort(9256).containerPort(9256).jobName("process")
                .defaultEnv(Map.of()).command(List.of())
                .requiredParams(List.of("PROCESS_NAMES"))
                .scrapeTarget("ops-process-exporter:9256").category("system").build());

        // Windows Exporter（远程 Windows 主机监控，端口 9182）
        register(ExporterTemplate.builder()
                .type("windows").displayName("Windows Exporter")
                .image("")  // 无 Docker 镜像，windows_exporter 直接在 Windows 上作服务运行
                .containerNamePrefix("ops-windows-exporter")
                .metricsPort(9182).containerPort(9182).jobName("windows")
                .defaultEnv(Map.of()).command(List.of())
                .requiredParams(List.of())
                .dockerRunHint("# Windows 上以 exe 服务运行，无需 Docker\n" +
                        "# 下载: https://github.com/prometheus-community/windows_exporter/releases\n" +
                        "# 安装: .\\windows_exporter.exe --telemetry.addr :9182 install")
                .scrapeTarget("").category("system").build());
    }

    private void register(ExporterTemplate template) {
        templates.put(template.getType(), template);
    }

    public List<ExporterTemplate> listAll() {
        return new ArrayList<>(templates.values());
    }

    public ExporterTemplate get(String type) {
        return templates.get(type);
    }

    public boolean exists(String type) {
        return templates.containsKey(type);
    }
}