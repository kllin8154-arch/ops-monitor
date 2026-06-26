package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CMDB 资产模型 — 主机资产
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Asset {

    private String id;
    private String hostname;
    private String ip;
    private String os;
    private String environment;  // prod / staging / dev
    private String project;
    private String owner;
    private String description;

    /** 关联的 Exporter ID 列表 */
    private List<String> exporterIds;

    /** 关联的 Server Node ID */
    private String serverId;

    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    @Builder.Default
    private long updatedAt = System.currentTimeMillis();
}