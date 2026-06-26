package com.opsmonitor.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Prometheus 查询服务实现
 *
 * Phase2修复：双数据源查询策略
 * - 优先查询 VictoriaMetrics（:8428）— Grafana 仪表盘同源，数据最全
 * - fallback 到 Prometheus（:9090）— 保证兼容性
 * - 两者都无结果才返回 empty
 *
 * 架构背景：
 *   Prometheus → remote_write → VictoriaMetrics（长期存储365天）
 *   Grafana 仪表盘数据源 = VictoriaMetrics
 *   ServiceStatusServiceImpl 需要与 Grafana 查同一数据源，否则显示不一致
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrometheusQueryServiceImpl implements PrometheusQueryService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 获取 VictoriaMetrics 查询 URL（从配置读取，默认 127.0.0.1:8428）
     */
    private String getVictoriaUrl() {
        String base = properties.getVictoria().getUrl();
        if (base == null || base.isBlank()) base = "http://127.0.0.1:8428";
        // 确保无末尾斜杠
        return base.replaceAll("/+$", "");
    }

    /**
     * 执行即时查询，返回第一个结果的数值
     *
     * Phase2修复：先查 VictoriaMetrics，无数据 fallback 到 Prometheus
     * VictoriaMetrics 兼容 Prometheus HTTP API，使用相同接口
     */
    @Override
    public Optional<Double> queryScalar(String promql) {
        // 1. 先查 VictoriaMetrics（与 Grafana 同源）
        Optional<Double> result = querySingleEndpoint(
                getVictoriaUrl() + "/api/v1/query", promql, "VictoriaMetrics");
        if (result.isPresent()) return result;

        // 2. fallback 到 Prometheus
        String promUrl = String.format("http://127.0.0.1:%d/api/v1/query",
                properties.getPrometheus().getPort());
        return querySingleEndpoint(promUrl, promql, "Prometheus");
    }

    /**
     * 向单个端点执行 PromQL 即时查询
     */
    private Optional<Double> querySingleEndpoint(String baseUrl, String promql, String sourceName) {
        try {
            String encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8);
            String url = baseUrl + "?query=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("[{}] query 非 200: {}", sourceName, response.statusCode());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                return Optional.empty();
            }

            JsonNode results = root.path("data").path("result");
            if (results.isArray() && !results.isEmpty()) {
                String valueStr = results.get(0).path("value").get(1).asText();
                // 防护 NaN / Inf 等 Prometheus/Victoria 特殊返回值
                if ("NaN".equalsIgnoreCase(valueStr) || "Inf".equalsIgnoreCase(valueStr)
                        || "+Inf".equalsIgnoreCase(valueStr) || "-Inf".equalsIgnoreCase(valueStr)) {
                    log.debug("[{}] 返回特殊值: {}", sourceName, valueStr);
                    return Optional.empty();
                }
                double parsed = Double.parseDouble(valueStr);
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    return Optional.empty();
                }
                log.debug("[{}] PromQL 查询成功: {:.2f}", sourceName, parsed);
                return Optional.of(parsed);
            }
            return Optional.empty();

        } catch (Exception e) {
            log.debug("[{}] query 异常 [{}]: {}", sourceName, promql, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 查询所有 job 的 up 状态
     * Phase2修复：同样先查 VictoriaMetrics，fallback 到 Prometheus
     */
    @Override
    public Map<String, Integer> queryAllJobStatus() {
        // 先查 Victoria
        Map<String, Integer> result = queryJobStatusFromEndpoint(
                getVictoriaUrl() + "/api/v1/query", "VictoriaMetrics");
        if (!result.isEmpty()) return result;

        // fallback 到 Prometheus
        String promUrl = String.format("http://127.0.0.1:%d/api/v1/query",
                properties.getPrometheus().getPort());
        return queryJobStatusFromEndpoint(promUrl, "Prometheus");
    }

    private Map<String, Integer> queryJobStatusFromEndpoint(String baseUrl, String sourceName) {
        Map<String, Integer> result = new LinkedHashMap<>();
        try {
            String url = baseUrl + "?query=up";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return result;

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("data").path("result");

            if (results.isArray()) {
                for (JsonNode item : results) {
                    String job = item.path("metric").path("job").asText("");
                    String valueStr = item.path("value").get(1).asText("0");
                    int value = "1".equals(valueStr) ? 1 : 0;
                    result.put(job, value);
                }
            }
        } catch (Exception e) {
            log.debug("[{}] 查询 job 状态异常: {}", sourceName, e.getMessage());
        }
        return result;
    }

    /**
     * 查询所有 managed Exporter 的 up 状态 (9G-1)
     * Phase2修复：先查 VictoriaMetrics，fallback 到 Prometheus
     */
    @Override
    public Map<String, Integer> queryExporterUpStatus() {
        String promql = "up{managed_by=\"ops-monitor\"}";

        // 先查 VictoriaMetrics
        Map<String, Integer> result = queryExporterUpFromEndpoint(
                getVictoriaUrl() + "/api/v1/query", promql, "VictoriaMetrics");
        if (!result.isEmpty()) return result;

        // fallback 到 Prometheus
        String promUrl = String.format("http://127.0.0.1:%d/api/v1/query",
                properties.getPrometheus().getPort());
        return queryExporterUpFromEndpoint(promUrl, promql, "Prometheus");
    }

    private Map<String, Integer> queryExporterUpFromEndpoint(String baseUrl, String promql, String sourceName) {
        Map<String, Integer> result = new LinkedHashMap<>();
        try {
            String encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8);
            String url = baseUrl + "?query=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).GET()
                    .timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return result;

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("data").path("result");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    String exporterId = item.path("metric").path("exporter_id").asText("");
                    String instance   = item.path("metric").path("instance").asText("");
                    String valueStr   = item.path("value").get(1).asText("0");
                    int value = "1".equals(valueStr) ? 1 : 0;
                    String key = !exporterId.isEmpty() ? exporterId : instance;
                    if (!key.isEmpty()) result.put(key, value);
                }
            }
        } catch (Exception e) {
            log.debug("[{}] 查询 Exporter up 状态异常: {}", sourceName, e.getMessage());
        }
        return result;
    }
}