package com.opsmonitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.AggregatedStatus;
import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.service.ServiceStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统状态聚合 API
 * 从 Prometheus + Docker 聚合全局状态
 *
 * v2.6 新增：
 * - GET /api/system/rules  — 查询 Prometheus 已加载的规则组（含 recording_rules 验证）
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemStatusController {

    private final ServiceStatusService statusService;
    private final OpsMonitorProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 获取系统聚合状态
     * 数据源: Prometheus API + Docker API
     */
    @GetMapping("/status")
    public ApiResponse<AggregatedStatus> status() {
        return ApiResponse.ok(statusService.getAggregatedStatus());
    }

    /**
     * v2.6: 查询 Prometheus 已加载的规则组
     *
     * 用途：验证 recording_rules.yml 和 alert.rules.yml 是否被 Prometheus 正确加载
     *
     * 返回结构：
     * {
     *   "totalGroups": 3,
     *   "recordingRules": 12,   ← recording rules 数量
     *   "alertRules": 6,        ← alert rules 数量
     *   "groups": [ { "name": "sli_recording", "rules": [...] } ],
     *   "recordingRulesLoaded": true,   ← recording_rules.yml 是否已加载
     *   "source": "prometheus"
     * }
     */
    @GetMapping("/rules")
    public ApiResponse<Map<String, Object>> rules() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String promUrl = String.format("http://127.0.0.1:%d/api/v1/rules",
                    properties.getPrometheus().getPort());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(promUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                result.put("error", "Prometheus 返回 HTTP " + response.statusCode());
                result.put("recordingRulesLoaded", false);
                return ApiResponse.ok(result);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode groups = root.path("data").path("groups");

            int totalGroups = 0;
            int recordingCount = 0;
            int alertCount = 0;
            boolean recordingRulesLoaded = false;
            List<Map<String, Object>> groupSummary = new ArrayList<>();

            if (groups.isArray()) {
                totalGroups = groups.size();
                for (JsonNode grp : groups) {
                    String grpName = grp.path("name").asText();
                    JsonNode rules = grp.path("rules");
                    int grpRec = 0, grpAlert = 0;
                    if (rules.isArray()) {
                        for (JsonNode rule : rules) {
                            String type = rule.path("type").asText();
                            if ("recording".equals(type)) { grpRec++; recordingCount++; }
                            else if ("alerting".equals(type)) { grpAlert++; alertCount++; }
                        }
                    }
                    // sli_recording / exporter_sli / slo_error_budget 均来自 recording_rules.yml
                    if ("sli_recording".equals(grpName) || "exporter_sli".equals(grpName)
                            || "slo_error_budget".equals(grpName)) {
                        recordingRulesLoaded = true;
                    }
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("name", grpName);
                    g.put("recordingRules", grpRec);
                    g.put("alertRules", grpAlert);
                    groupSummary.add(g);
                }
            }

            result.put("totalGroups", totalGroups);
            result.put("recordingRules", recordingCount);
            result.put("alertRules", alertCount);
            result.put("recordingRulesLoaded", recordingRulesLoaded);
            result.put("groups", groupSummary);
            result.put("source", "prometheus");

        } catch (Exception e) {
            log.warn("[SystemStatus] 查询 Prometheus rules 失败: {}", e.getMessage());
            result.put("error", "无法连接 Prometheus: " + e.getMessage());
            result.put("recordingRulesLoaded", false);
        }
        return ApiResponse.ok(result);
    }
}