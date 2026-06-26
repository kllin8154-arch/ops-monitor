package com.opsmonitor.agent;

import com.opsmonitor.model.ExporterTemplate;
import com.opsmonitor.monitor.ExporterTemplateRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 本地 Exporter 管理器 (10A-4)
 *
 * 管理 Agent 节点上的 Exporter 实例。
 * 与中心的 ExporterManagerImpl 不同：
 * - 本类运行在 Agent 端，只管理本地 Exporter
 * - 生成 docker run 命令或 binary 启动脚本
 * - 向 Control Plane 汇报已部署的 Exporter
 *
 * Agent 端 Exporter 不需要 Prometheus scrape，
 * 指标通过 Remote Write 直接推送到 VictoriaMetrics。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExporterManager {

    private final ExporterTemplateRegistry templateRegistry;

    /** 本地已部署的 Exporter: type → LocalExporter */
    private final Map<String, LocalExporter> localExporters = new ConcurrentHashMap<>();

    /**
     * 注册本地 Exporter（记录状态，实际部署由外部执行）
     */
    public LocalExporter registerLocal(String type, String targetAddress, int port) {
        ExporterTemplate tpl = templateRegistry.get(type);
        if (tpl == null) {
            throw new IllegalArgumentException("未知 Exporter 类型: " + type);
        }

        int actualPort = port > 0 ? port : tpl.getMetricsPort();
        String id = type + "-" + actualPort;

        LocalExporter exporter = LocalExporter.builder()
                .id(id)
                .type(type)
                .targetAddress(targetAddress)
                .metricsPort(actualPort)
                .image(tpl.getImage())
                .containerPort(tpl.getContainerPort())
                .status("REGISTERED")
                .registeredAt(System.currentTimeMillis())
                .build();

        localExporters.put(id, exporter);
        log.info("Agent 本地 Exporter 已注册: {} (port={})", type, actualPort);
        return exporter;
    }

    /**
     * 生成 Docker 部署命令
     */
    public String generateDockerCommand(String exporterId) {
        LocalExporter exp = localExporters.get(exporterId);
        if (exp == null) throw new IllegalArgumentException("Exporter 不存在: " + exporterId);

        return String.format("docker run -d --name ops-%s-%d --restart unless-stopped -p %d:%d %s",
                exp.getType(), exp.getMetricsPort(), exp.getMetricsPort(),
                exp.getContainerPort(), exp.getImage());
    }

    /**
     * 标记 Exporter 为运行状态
     */
    public void markRunning(String exporterId) {
        LocalExporter exp = localExporters.get(exporterId);
        if (exp != null) exp.setStatus("RUNNING");
    }

    /**
     * 注销本地 Exporter
     */
    public boolean unregister(String exporterId) {
        return localExporters.remove(exporterId) != null;
    }

    /**
     * 获取所有本地 Exporter
     */
    public List<LocalExporter> listLocal() {
        return new ArrayList<>(localExporters.values());
    }

    /**
     * 获取已注册类型列表（用于 Agent 心跳上报）
     */
    public List<String> getDeployedTypes() {
        return localExporters.values().stream()
                .map(LocalExporter::getType)
                .distinct()
                .toList();
    }

    @Data
    @Builder
    public static class LocalExporter {
        private String id;
        private String type;
        private String targetAddress;
        private int metricsPort;
        private int containerPort;
        private String image;
        private String status; // REGISTERED / RUNNING / STOPPED / ERROR
        private long registeredAt;
    }
}