package com.opsmonitor.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多租户服务 (10D-4)
 *
 * 全链路租户隔离：
 * - 所有资源绑定 tenant
 * - 查询自动过滤 tenant
 * - Dashboard 自动隔离（通过 $tenant 变量）
 * - AlertRule 按 tenant 分组
 * - Prometheus labels 注入 tenant
 *
 * 默认租户：default
 */
@Slf4j
@Service
public class TenantService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();

    public static final String DEFAULT_TENANT = "default";

    public TenantService(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        loadFromJson();
        // 确保 default 租户存在
        tenants.putIfAbsent(DEFAULT_TENANT, Tenant.builder()
                .tenantId(DEFAULT_TENANT)
                .displayName("默认租户")
                .status("ACTIVE")
                .createdAt(System.currentTimeMillis())
                .quotaMaxAgents(100)
                .quotaMaxExporters(500)
                .quotaMaxProjects(50)
                .build());
        log.info("[TenantService] 多租户服务就绪, 共 {} 个租户", tenants.size());
    }

    public Tenant createTenant(Tenant tenant) {
        if (tenant.getTenantId() == null || tenant.getTenantId().isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        if (tenants.containsKey(tenant.getTenantId())) {
            throw new IllegalArgumentException("租户已存在: " + tenant.getTenantId());
        }
        tenant.setCreatedAt(System.currentTimeMillis());
        tenant.setStatus("ACTIVE");
        tenants.put(tenant.getTenantId(), tenant);
        saveToJson();
        log.info("[TenantService] 创建租户: {}", tenant.getTenantId());
        return tenant;
    }

    public Tenant getTenant(String tenantId) {
        return tenants.get(tenantId != null ? tenantId : DEFAULT_TENANT);
    }

    public List<Tenant> listTenants() {
        return new ArrayList<>(tenants.values());
    }

    // v2.22: 更新租户配额和状态
    public Tenant updateTenant(String tenantId, Map<String, Object> body) {
        Tenant t = tenants.get(tenantId);
        if (t == null) throw new IllegalArgumentException("租户不存在: " + tenantId);
        if (body.containsKey("displayName")) t.setDisplayName((String) body.get("displayName"));
        if (body.containsKey("status")) t.setStatus((String) body.get("status"));
        if (body.containsKey("quotaMaxAgents")) t.setQuotaMaxAgents(((Number) body.get("quotaMaxAgents")).intValue());
        if (body.containsKey("quotaMaxExporters")) t.setQuotaMaxExporters(((Number) body.get("quotaMaxExporters")).intValue());
        saveToJson();
        log.info("[TenantService] 更新租户: {}", tenantId);
        return t;
    }

    public boolean deleteTenant(String tenantId) {
        if (DEFAULT_TENANT.equals(tenantId)) {
            throw new IllegalArgumentException("不能删除默认租户");
        }
        Tenant removed = tenants.remove(tenantId);
        if (removed != null) {
            saveToJson();
            return true;
        }
        return false;
    }

    /**
     * 检查租户配额
     */
    public boolean checkQuota(String tenantId, String resourceKind, int currentCount) {
        Tenant t = getTenant(tenantId);
        if (t == null) return false;
        return switch (resourceKind) {
            case "Agent" -> currentCount < t.getQuotaMaxAgents();
            case "Exporter" -> currentCount < t.getQuotaMaxExporters();
            case "Project" -> currentCount < t.getQuotaMaxProjects();
            default -> true;
        };
    }

    /**
     * 获取租户的 Prometheus label 注入
     */
    public Map<String, String> getTenantLabels(String tenantId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("tenant", tenantId != null ? tenantId : DEFAULT_TENANT);
        return labels;
    }

    // ==================== 持久化 ====================

    private Path getTenantsFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "tenants.json").normalize();
    }

    private void saveToJson() {
        try {
            Path path = getTenantsFile();
            Path tmp  = path.resolveSibling("tenants.json.tmp");
            Files.createDirectories(path.getParent());
            mapper.writeValue(tmp.toFile(), new ArrayList<>(tenants.values()));
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存 tenants.json 失败: {}", e.getMessage());
        }
    }

    private void loadFromJson() {
        Path path = getTenantsFile();
        if (!Files.exists(path)) return;
        try {
            List<Tenant> list = mapper.readValue(path.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Tenant.class));
            for (Tenant t : list) tenants.put(t.getTenantId(), t);
        } catch (IOException e) {
            log.error("加载 tenants.json 失败: {}", e.getMessage());
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Tenant {
        private String tenantId;
        private String displayName;
        private String status; // ACTIVE / SUSPENDED
        private long createdAt;
        @Builder.Default private int quotaMaxAgents = 100;
        @Builder.Default private int quotaMaxExporters = 500;
        @Builder.Default private int quotaMaxProjects = 50;
    }
}