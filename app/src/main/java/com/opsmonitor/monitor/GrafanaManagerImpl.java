package com.opsmonitor.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Grafana 管理器实现
 *
 * 轮询机制：
 *   GET /api/health → HTTP 200 且 JSON 包含 "database":"ok" → ready
 *   最多 20 次重试（60s），间隔 3s
 *   如果 200 但无 database 字段 → 也视为就绪（兼容不同 Grafana 版本）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrafanaManagerImpl implements GrafanaManager {

    private final OpsMonitorProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_WAIT_SECONDS = 60;
    private static final int RETRY_INTERVAL_SECONDS = 3;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ==================== 就绪检测 ====================

    @Override
    public boolean waitUntilReady() {
        int maxRetries = MAX_WAIT_SECONDS / RETRY_INTERVAL_SECONDS;
        String healthUrl = getGrafanaApiUrl() + "/api/health";

        log.info("等待 Grafana 就绪 (URL: {}, 最多 {}s, 间隔 {}s)...",
                healthUrl, MAX_WAIT_SECONDS, RETRY_INTERVAL_SECONDS);

        for (int i = 1; i <= maxRetries; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(healthUrl))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                String body = response.body();

                if (status == 200) {
                    // 尝试解析 JSON
                    try {
                        JsonNode json = objectMapper.readTree(body);
                        String dbStatus = json.has("database")
                                ? json.get("database").asText() : null;

                        if ("ok".equals(dbStatus)) {
                            log.info("✅ Grafana 已就绪 (第 {} 次检测, 约 {}s), database=ok",
                                    i, i * RETRY_INTERVAL_SECONDS);
                            return true;
                        }

                        // 如果 200 但无 database 字段（某些版本），也视为就绪
                        if (dbStatus == null) {
                            log.info("✅ Grafana 响应 200（无 database 字段，视为就绪）, body={}",
                                    body.length() > 200 ? body.substring(0, 200) : body);
                            return true;
                        }

                        // database 存在但不是 "ok"
                        log.info("Grafana 检测 {}/{}: HTTP 200, database={}, 继续等待...",
                                i, maxRetries, dbStatus);

                    } catch (Exception parseEx) {
                        // JSON 解析失败但 200 → 也视为就绪
                        log.info("✅ Grafana 响应 200（JSON 解析异常，视为就绪）, body={}",
                                body.length() > 100 ? body.substring(0, 100) : body);
                        return true;
                    }
                } else {
                    log.info("Grafana 检测 {}/{}: HTTP {} (等待中...)", i, maxRetries, status);
                }
            } catch (java.net.ConnectException e) {
                log.info("Grafana 检测 {}/{}: 连接拒绝 (容器可能还在启动...)", i, maxRetries);
            } catch (Exception e) {
                log.info("Grafana 检测 {}/{}: {} ({})",
                        i, maxRetries, e.getClass().getSimpleName(), e.getMessage());
            }

            // 等待下次重试
            try {
                Thread.sleep(RETRY_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Grafana 就绪检测被中断");
                return false;
            }
        }

        log.warn("⚠️ Grafana 未在 {}s 内就绪，跳过自动初始化", MAX_WAIT_SECONDS);
        return false;
    }

    // ==================== 数据源管理 ====================

    @Override
    public boolean datasourceExists(String name) {
        try {
            String url = getGrafanaApiUrl() + "/api/datasources";
            log.info("检查 Grafana 数据源是否存在: name={}, url={}", name, url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", getAuthHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200) {
                JsonNode datasources = objectMapper.readTree(response.body());
                if (datasources.isArray()) {
                    for (JsonNode ds : datasources) {
                        if (ds.has("name") && name.equals(ds.get("name").asText())) {
                            log.info("数据源 '{}' 已存在 (id={})",
                                    name, ds.has("id") ? ds.get("id").asInt() : "?");
                            return true;
                        }
                    }
                }
                log.info("数据源 '{}' 不存在，当前共 {} 个数据源", name,
                        datasources.isArray() ? datasources.size() : 0);
                return false;
            } else if (status == 401 || status == 403) {
                log.warn("查询数据源认证失败: HTTP {} (Provisioning 会自动生效)", status);
                return false;
            } else {
                log.error("查询数据源失败: HTTP {}, body={}", status, response.body());
                return false;
            }

        } catch (Exception e) {
            log.error("查询数据源异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void createPrometheusDatasource() {
        try {
            String promInternalUrl = String.format("http://ops-prometheus:%d",
                    properties.getPrometheus().getPort());

            String body = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("name", "Prometheus");
                put("type", "prometheus");
                put("access", "proxy");
                put("url", promInternalUrl);
                put("isDefault", true);
            }});

            String apiUrl = getGrafanaApiUrl() + "/api/datasources";
            log.info("创建 Grafana 数据源: POST {} body={}", apiUrl, body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", getAuthHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            String respBody = response.body();

            if (status == 200) {
                log.info("✅ Grafana Prometheus 数据源创建成功");
            } else if (status == 409) {
                log.info("Grafana Prometheus 数据源已存在 (HTTP 409)");
            } else if (status == 401 || status == 403) {
                log.warn("创建数据源认证失败: HTTP {} (Provisioning 会自动生效)", status);
            } else {
                log.error("❌ 创建数据源失败: HTTP {}, body={}", status, respBody);
            }

        } catch (Exception e) {
            log.error("❌ 创建数据源异常: {}", e.getMessage(), e);
        }
    }

    // ==================== 初始化总流程 ====================

    @Override
    public void initializeGrafana() {
        log.info("========== Grafana 自动初始化开始 ==========");

        try {
            // Step 1: 等待就绪
            if (!waitUntilReady()) {
                log.warn("⚠️ Grafana 未就绪，但数据源已通过 Provisioning 文件配置");
                log.warn("   Grafana 启动后会自动加载 provisioning/datasources/prometheus.yml");
                return;
            }

            // Step 2: 验证数据源（Provisioning 方式已在 Grafana 启动时自动创建）
            if (datasourceExists("Prometheus")) {
                log.info("✅ Prometheus 数据源已存在（Provisioning 自动创建）");
                // 9F-2: 触发 Dashboard provisioning 刷新
                reloadDashboards();
                log.info("========== Grafana 初始化完成 ==========");
                return;
            }

            // Step 3: Provisioning 未生效时尝试 API 创建（兜底）
            log.info("Provisioning 数据源未检测到，尝试 API 方式创建...");
            createPrometheusDatasource();

            // Step 4: 再次验证
            if (datasourceExists("Prometheus")) {
                log.info("========== Grafana 初始化完成（API 创建成功） ==========");
            } else {
                log.warn("⚠️ 数据源创建未成功，Grafana 重启后 Provisioning 会自动生效");
            }

        } catch (Exception e) {
            log.error("Grafana 初始化异常（不影响系统运行）: {}", e.getMessage());
            log.info("数据源将在 Grafana 重启后通过 Provisioning 自动创建");
        }
    }

    // ==================== 保留方法 ====================

    @Override
    public boolean importDashboard(String dashboardJson) {
        try {
            String body = """
                    {
                        "dashboard": %s,
                        "overwrite": true,
                        "folderId": 0
                    }
                    """.formatted(dashboardJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getGrafanaApiUrl() + "/api/dashboards/db"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", getAuthHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("Dashboard 导入成功");
                return true;
            } else {
                log.error("Dashboard 导入失败: HTTP {} {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Dashboard 导入异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isRunning() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getGrafanaApiUrl() + "/api/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getEmbedUrl() {
        return String.format("http://127.0.0.1:%d", properties.getGrafana().getPort());
    }

    /**
     * 触发 Grafana Dashboard provisioning 重新加载 (9F-2)
     * Grafana 不会自动检测 provisioning 文件变化，必须调用此 API
     */
    @Override
    public boolean reloadDashboards() {
        try {
            String url = getGrafanaApiUrl() + "/api/admin/provisioning/dashboards/reload";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", getAuthHeader())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("Grafana Dashboard provisioning 已刷新");
                return true;
            } else {
                log.warn("Grafana Dashboard 刷新失败: HTTP {} {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.warn("Grafana Dashboard 刷新异常: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 私有方法 ====================

    private String getGrafanaApiUrl() {
        return String.format("http://127.0.0.1:%d", properties.getGrafana().getPort());
    }

    private String getAuthHeader() {
        // N27修复：不在日志中打印任何凭据信息（含 Base64 前缀）
        String credentials = properties.getGrafana().getAdminUser() + ":"
                + properties.getGrafana().getAdminPassword();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}