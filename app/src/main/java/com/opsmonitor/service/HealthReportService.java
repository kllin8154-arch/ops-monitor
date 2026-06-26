package com.opsmonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.docker.DockerService;
import com.opsmonitor.model.AggregatedStatus;
import com.opsmonitor.model.ExporterInstance;
import com.opsmonitor.monitor.ExporterManager;
import com.opsmonitor.monitor.GrafanaManager;
import com.opsmonitor.monitor.PrometheusManager;
import com.opsmonitor.sentinel.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * v2.20: 定时系统健康报告服务
 * 每天 8:00/12:00/18:00 自动生成健康报告，写入 data/ 目录
 * 如配置通知渠道，自动推送摘要
 */
@Slf4j
@Service
public class HealthReportService {

    private final ServiceStatusService statusService;
    private final ExporterManager exporterManager;
    private final IncidentService incidentService;
    private final DockerService dockerService;
    private final OpsMonitorProperties properties;
    private final ObjectMapper jsonMapper;

    public HealthReportService(ServiceStatusService statusService,
                                ExporterManager exporterManager,
                                IncidentService incidentService,
                                DockerService dockerService,
                                OpsMonitorProperties properties) {
        this.statusService = statusService;
        this.exporterManager = exporterManager;
        this.incidentService = incidentService;
        this.dockerService = dockerService;
        this.properties = properties;
        this.jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Scheduled(cron = "0 0 8,12,18 * * ?")
    public void scheduledReport() {
        try {
            Map<String, Object> report = generateReport();
            saveReport(report);
            tryPushNotification(report);
            cleanOldReports(30);
        } catch (Exception e) {
            log.error("健康报告生成失败: {}", e.getMessage(), e);
        }
    }

    /** 生成并保存报告（手动触发用） */
    public Map<String, Object> generateAndSave() {
        Map<String, Object> report = generateReport();
        saveReport(report);
        tryPushNotification(report);
        cleanOldReports(30);
        return report;
    }

    public Map<String, Object> generateReport() {
        AggregatedStatus status = statusService.getAggregatedStatus();
        List<ExporterInstance> exporters = exporterManager.listExporters();
        List<com.opsmonitor.sentinel.Incident> openIncidents = incidentService.listOpen();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("version", "v2.20");

        // 系统资源
        Map<String, Object> resources = new LinkedHashMap<>();
        double cpu = status.getCpuUsage();
        double mem = status.getMemoryUsage();
        double disk = status.getDiskUsage();
        resources.put("cpuUsage", cpu);
        resources.put("memoryUsage", mem);
        resources.put("diskUsage", disk);
        resources.put("cpuLevel", cpu < 0 ? "N/A" : cpu > 85 ? "CRITICAL" : cpu > 60 ? "WARN" : "OK");
        resources.put("memoryLevel", mem < 0 ? "N/A" : mem > 90 ? "CRITICAL" : mem > 75 ? "WARN" : "OK");
        resources.put("diskLevel", disk < 0 ? "N/A" : disk > 85 ? "CRITICAL" : disk > 75 ? "WARN" : "OK");
        report.put("resources", resources);

        // Exporter 统计
        long exporterUp = exporters.stream().filter(e -> "running".equalsIgnoreCase(e.getState())).count();
        Map<String, Object> exporterStats = new LinkedHashMap<>();
        exporterStats.put("total", exporters.size());
        exporterStats.put("up", exporterUp);
        exporterStats.put("down", exporters.size() - exporterUp);
        report.put("exporters", exporterStats);

        // Incident 统计
        Map<String, Object> incidentStats = new LinkedHashMap<>();
        incidentStats.put("openCount", openIncidents.size());
        incidentStats.put("p0Count", openIncidents.stream().filter(i -> "P0".equals(i.getSeverity())).count());
        incidentStats.put("p1Count", openIncidents.stream().filter(i -> "P1".equals(i.getSeverity())).count());
        report.put("incidents", incidentStats);

        // Docker 容器统计
        try {
            var containers = dockerService.listContainers(true);
            long running = containers.stream().filter(c -> "running".equalsIgnoreCase(c.getState())).count();
            Map<String, Object> dockerStats = new LinkedHashMap<>();
            dockerStats.put("total", containers.size());
            dockerStats.put("running", running);
            report.put("containers", dockerStats);
        } catch (Exception e) {
            report.put("containers", Map.of("error", "Docker 不可用"));
        }

        // 基础设施状态
        Map<String, Object> infra = new LinkedHashMap<>();
        infra.put("docker", status.getDocker());
        infra.put("prometheus", status.getPrometheus());
        infra.put("grafana", status.getGrafana());
        report.put("infrastructure", infra);

        // 健康等级
        List<String> issues = new ArrayList<>();
        if (cpu > 85) issues.add("CPU过高");
        if (mem > 90) issues.add("内存耗尽风险");
        if (disk > 85) issues.add("磁盘不足");
        if (exporterUp < exporters.size()) issues.add("Exporter离线");
        report.put("healthLevel", issues.isEmpty() ? "OK" : issues.size() <= 2 ? "WARN" : "CRITICAL");
        report.put("issues", issues);

        return report;
    }

    private void saveReport(Map<String, Object> report) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
            Path dir = getReportDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("health-report-" + timestamp + ".json");
            Path tmp = dir.resolve("health-report-" + timestamp + ".json.tmp");
            jsonMapper.writeValue(tmp.toFile(), report);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("健康报告已保存: {}", file.getFileName());
        } catch (IOException e) {
            log.error("保存健康报告失败: {}", e.getMessage());
        }
    }

    private void tryPushNotification(Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) report.get("resources");
        Map<?, ?> expStats = (Map<?, ?>) report.get("exporters");
        Map<?, ?> incStats = (Map<?, ?>) report.get("incidents");
        String level = (String) report.get("healthLevel");
        String summary = String.format("[OpsMonitor 健康报告] CPU=%.1f%% MEM=%.1f%% DISK=%.1f%% | Exporter=%d/%d | Incident=%d | 等级=%s",
                res.get("cpuUsage"), res.get("memoryUsage"), res.get("diskUsage"),
                expStats.get("up"), expStats.get("total"),
                incStats.get("openCount"), level);

        // 仅异常时推送通知（NotificationDispatcher 目前仅支持 Alert 对象，健康报告通知待后续适配）
        if (!"OK".equals(level)) {
            log.warn("⚠️ 健康报告异常: {}", summary);
        } else {
            log.info(summary);
        }
    }

    public Map<String, Object> getLatestReport() {
        try {
            Path dir = getReportDir();
            if (!Files.exists(dir)) return null;
            Optional<Path> latest = Files.list(dir)
                    .filter(f -> f.getFileName().toString().startsWith("health-report-"))
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .max(Comparator.naturalOrder());
            if (latest.isPresent()) {
                return jsonMapper.readValue(latest.get().toFile(), Map.class);
            }
        } catch (Exception e) {
            log.warn("读取最新报告失败: {}", e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> listReports() {
        List<Map<String, Object>> reports = new ArrayList<>();
        try {
            Path dir = getReportDir();
            if (!Files.exists(dir)) return reports;
            Files.list(dir)
                    .filter(f -> f.getFileName().toString().startsWith("health-report-"))
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder())
                    .limit(50)
                    .forEach(f -> {
                        try {
                            reports.add(jsonMapper.readValue(f.toFile(), Map.class));
                        } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            log.warn("列出报告失败: {}", e.getMessage());
        }
        return reports;
    }

    private void cleanOldReports(int retainDays) {
        try {
            Path dir = getReportDir();
            if (!Files.exists(dir)) return;
            LocalDateTime cutoff = LocalDateTime.now().minus(retainDays, ChronoUnit.DAYS);
            Files.list(dir)
                    .filter(f -> f.getFileName().toString().startsWith("health-report-"))
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> {
                        try {
                            if (Files.getLastModifiedTime(f).toInstant().isBefore(
                                    java.time.Instant.from(cutoff.atZone(java.time.ZoneId.systemDefault())))) {
                                Files.delete(f);
                                log.info("清理过期健康报告: {}", f.getFileName());
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            log.warn("清理旧报告失败: {}", e.getMessage());
        }
    }

    private Path getReportDir() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "health-reports").normalize();
    }
}
