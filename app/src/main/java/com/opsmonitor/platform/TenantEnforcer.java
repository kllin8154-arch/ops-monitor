package com.opsmonitor.platform;

import com.opsmonitor.platform.model.ManagedResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 租户强制隔离 (10D.1 Hotfix-3)
 *
 * 所有资源操作入口必须经过 TenantEnforcer：
 * - 写入前：校验 tenant 有效性 + 配额检查
 * - 查询时：自动注入 tenant 过滤
 * - 命令下发：校验 Agent 归属 tenant
 * - 配置变更：校验 config 归属 tenant
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantEnforcer {

    private final TenantService tenantService;

    /**
     * 写入前校验
     * @throws IllegalArgumentException 租户无效或配额超限
     */
    public void enforceWrite(ManagedResource resource, int currentCount) {
        String tenant = normalizeTenant(resource.getTenant());
        resource.setTenant(tenant);

        // 校验租户存在
        TenantService.Tenant t = tenantService.getTenant(tenant);
        if (t == null) {
            throw new IllegalArgumentException("租户不存在: " + tenant);
        }
        if ("SUSPENDED".equals(t.getStatus())) {
            throw new IllegalArgumentException("租户已暂停: " + tenant);
        }

        // 配额检查
        if (!tenantService.checkQuota(tenant, resource.getKind(), currentCount)) {
            throw new IllegalArgumentException(String.format(
                    "租户 %s 的 %s 配额已满 (当前 %d)", tenant, resource.getKind(), currentCount));
        }
    }

    /**
     * 查询前注入 tenant（如果未指定，使用 default）
     */
    public String enforceQuery(String requestedTenant) {
        return normalizeTenant(requestedTenant);
    }

    /**
     * 校验 Agent 归属 tenant
     */
    public void enforceAgentAccess(String agentTenant, String requestTenant) {
        String normalized = normalizeTenant(requestTenant);
        if (!normalized.equals(normalizeTenant(agentTenant))) {
            throw new IllegalArgumentException("无权访问其他租户的 Agent");
        }
    }

    /**
     * 标准化 tenant（null/blank → default）
     */
    public String normalizeTenant(String tenant) {
        return (tenant == null || tenant.isBlank()) ? TenantService.DEFAULT_TENANT : tenant.trim();
    }
}