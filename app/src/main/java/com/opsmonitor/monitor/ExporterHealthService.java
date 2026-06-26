package com.opsmonitor.monitor;

import com.opsmonitor.model.ExporterInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Exporter 健康检测服务 (9G-1 优化版)
 *
 * 通过单次 Prometheus up{managed_by="ops-monitor"} 查询
 * 批量获取所有 Exporter 状态，替代逐个 HTTP 探测。
 *
 * 状态: UP / DOWN / PENDING / UNKNOWN
 */
@Slf4j
@Service
public class ExporterHealthService {

    private final ExporterManager exporterManager;
    private final PrometheusQueryService promQuery;
    private final Map<String, String> healthStatus = new ConcurrentHashMap<>();
    private volatile long lastCheckTime = 0;

    public ExporterHealthService(ExporterManager exporterManager, PrometheusQueryService promQuery) {
        this.exporterManager = exporterManager;
        this.promQuery = promQuery;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void checkAllExporters() {
        try {
            var exporters = exporterManager.listExporters();
            if (exporters.isEmpty()) return;

            Map<String, Integer> upStatus = promQuery.queryExporterUpStatus();
            int up = 0, down = 0, pending = 0;

            for (ExporterInstance exp : exporters) {
                Integer upVal = upStatus.get(exp.getId());
                if (upVal == null) upVal = upStatus.get(exp.getScrapeTarget());
                String status;
                if (upVal == null) { status = "PENDING"; pending++; }
                else if (upVal == 1) { status = "UP"; up++; }
                else { status = "DOWN"; down++; }
                healthStatus.put(exp.getId(), status);
            }

            var validIds = exporters.stream().map(ExporterInstance::getId).collect(Collectors.toSet());
            healthStatus.keySet().removeIf(id -> !validIds.contains(id));
            lastCheckTime = System.currentTimeMillis();
            log.debug("Exporter 健康检测: UP={}, DOWN={}, PENDING={}", up, down, pending);
        } catch (Exception e) {
            log.warn("Exporter 健康检测异常: {}", e.getMessage());
        }
    }

    public String getStatus(String exporterId) { return healthStatus.getOrDefault(exporterId, "UNKNOWN"); }
    public Map<String, String> getAllStatus() { return Map.copyOf(healthStatus); }
    public long getLastCheckTime() { return lastCheckTime; }
}