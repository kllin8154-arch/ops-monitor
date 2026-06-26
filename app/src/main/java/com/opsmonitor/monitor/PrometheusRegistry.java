package com.opsmonitor.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus Pool — 多 Prometheus 实例注册与路由
 *
 * 当前阶段：单实例模式（默认本地 Prometheus）
 * 扩展支持：注册多个 Prometheus 实例，按 hostname hash 分配
 *
 * 用于未来多集群监控场景：
 *   prom-cluster-1: 负责 server-01 ~ server-50
 *   prom-cluster-2: 负责 server-51 ~ server-100
 */
@Slf4j
@Component
public class PrometheusRegistry {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromInstance {
        private String id;
        private String name;
        private String url;      // http://prometheus:9090
        private boolean primary;
        @Builder.Default
        private boolean healthy = true;
    }

    private final ConcurrentHashMap<String, PromInstance> instances = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * 注册 Prometheus 实例
     */
    public void register(PromInstance instance) {
        instances.put(instance.getId(), instance);
        log.info("Prometheus 实例已注册: {} ({})", instance.getName(), instance.getUrl());
    }

    /**
     * 获取主 Prometheus（当前默认模式）
     */
    public PromInstance getPrimary() {
        return instances.values().stream()
                .filter(PromInstance::isPrimary)
                .findFirst()
                .orElse(null);
    }

    /**
     * 按 hostname hash 分配 Prometheus（多实例模式）
     */
    public PromInstance assignPrometheus(String hostname) {
        List<PromInstance> healthy = instances.values().stream()
                .filter(PromInstance::isHealthy)
                .toList();
        if (healthy.isEmpty()) return getPrimary();
        int idx = Math.abs(hostname.hashCode()) % healthy.size();
        return healthy.get(idx);
    }

    /**
     * 列出所有实例
     */
    public List<PromInstance> listInstances() {
        return new ArrayList<>(instances.values());
    }

    /**
     * 健康检查所有实例
     */
    public void healthCheckAll() {
        for (PromInstance inst : instances.values()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(inst.getUrl() + "/-/ready"))
                        .GET().timeout(Duration.ofSeconds(3)).build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                inst.setHealthy(response.statusCode() == 200);
            } catch (Exception e) {
                inst.setHealthy(false);
            }
        }
    }

    /**
     * 初始化默认本地实例
     */
    public void initDefault(int port) {
        if (instances.isEmpty()) {
            register(PromInstance.builder()
                    .id("local")
                    .name("本机 Prometheus")
                    .url("http://127.0.0.1:" + port)
                    .primary(true)
                    .build());
        }
    }
}