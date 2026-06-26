package com.opsmonitor.platform.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台统一资源基类 (10D-1)
 *
 * 所有可管理资源的抽象基类，类似 Kubernetes Resource。
 * 每个资源有：kind / name / tenant / labels / metadata。
 *
 * 派生：Project / Service / Instance / AgentResource / ExporterResource / AlertRuleResource / DashboardResource
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class ManagedResource {

    /** 资源类型 (Project / Service / Instance / Agent / Exporter / AlertRule / Dashboard) */
    private String kind;

    /** 资源唯一名称（在 tenant 内唯一） */
    private String name;

    /** 租户 ID（10D-4 多租户） */
    private String tenant;

    /** 资源标签 */
    private Map<String, String> labels;

    /** 资源注解（扩展元数据） */
    private Map<String, String> annotations;

    /** 创建时间 */
    private long createdAt;

    /** 最后更新时间 */
    private long updatedAt;

    /** 资源状态 */
    private String status;

    /** 版本号（乐观锁） */
    private int version;

    /**
     * 获取完全限定名: tenant/kind/name
     */
    public String getQualifiedName() {
        String t = (tenant != null && !tenant.isBlank()) ? tenant : "default";
        return t + "/" + kind + "/" + name;
    }

    /**
     * 确保 labels 和 annotations 非 null
     */
    public Map<String, String> safeLabels() {
        if (labels == null) labels = new LinkedHashMap<>();
        return labels;
    }

    public Map<String, String> safeAnnotations() {
        if (annotations == null) annotations = new LinkedHashMap<>();
        return annotations;
    }
}