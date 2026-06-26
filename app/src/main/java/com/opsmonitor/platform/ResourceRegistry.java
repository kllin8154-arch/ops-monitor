package com.opsmonitor.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.platform.model.ManagedResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 统一资源注册表 (10D-1)
 *
 * 管理所有平台资源的 CRUD + 查询 + 持久化。
 * key = qualifiedName (tenant/kind/name)
 *
 * 持久化：data/resources/{kind}.json
 */
@Slf4j
@Service
public class ResourceRegistry {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;

    /** 核心存储：qualifiedName → ManagedResource */
    private final Map<String, ManagedResource> resources = new ConcurrentHashMap<>();

    public ResourceRegistry(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        log.info("[ResourceRegistry] 统一资源注册表就绪");
    }

    /**
     * 注册/更新资源
     */
    public ManagedResource put(ManagedResource resource) {
        if (resource.getName() == null || resource.getName().isBlank()) {
            throw new IllegalArgumentException("资源名称不能为空");
        }
        if (resource.getKind() == null) {
            throw new IllegalArgumentException("资源类型不能为空");
        }
        if (resource.getTenant() == null || resource.getTenant().isBlank()) {
            resource.setTenant("default");
        }

        long now = System.currentTimeMillis();
        String key = resource.getQualifiedName();

        ManagedResource existing = resources.get(key);
        if (existing != null) {
            resource.setCreatedAt(existing.getCreatedAt());
            resource.setVersion(existing.getVersion() + 1);
        } else {
            resource.setCreatedAt(now);
            resource.setVersion(1);
        }
        resource.setUpdatedAt(now);

        resources.put(key, resource);
        log.debug("[ResourceRegistry] PUT {}", key);
        return resource;
    }

    /**
     * 获取资源
     */
    public ManagedResource get(String tenant, String kind, String name) {
        String key = (tenant != null ? tenant : "default") + "/" + kind + "/" + name;
        return resources.get(key);
    }

    /**
     * 删除资源
     */
    public boolean delete(String tenant, String kind, String name) {
        String key = (tenant != null ? tenant : "default") + "/" + kind + "/" + name;
        ManagedResource removed = resources.remove(key);
        if (removed != null) {
            log.debug("[ResourceRegistry] DELETE {}", key);
            return true;
        }
        return false;
    }

    /**
     * 按类型查询
     */
    public List<ManagedResource> listByKind(String kind) {
        return resources.values().stream()
                .filter(r -> kind.equals(r.getKind()))
                .collect(Collectors.toList());
    }

    /**
     * 按租户+类型查询
     */
    public List<ManagedResource> listByTenantAndKind(String tenant, String kind) {
        String t = (tenant != null) ? tenant : "default";
        return resources.values().stream()
                .filter(r -> t.equals(r.getTenant()) && kind.equals(r.getKind()))
                .collect(Collectors.toList());
    }

    /**
     * 按标签查询
     */
    public List<ManagedResource> listByLabel(String key, String value) {
        return resources.values().stream()
                .filter(r -> r.getLabels() != null && value.equals(r.getLabels().get(key)))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有资源数量
     */
    public Map<String, Long> countByKind() {
        return resources.values().stream()
                .collect(Collectors.groupingBy(ManagedResource::getKind, Collectors.counting()));
    }

    public int size() { return resources.size(); }
}