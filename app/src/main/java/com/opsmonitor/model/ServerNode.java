package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务器节点模型
 *
 * 表示一台被监控的服务器。
 * type:
 *   LOCAL   = 本机（Docker 模式，可创建容器）
 *   REMOTE  = 远程服务器（仅 exporter 模式，通过 IP 抓取指标）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerNode {

    /** 节点 ID（自动生成） */
    private String id;

    /** 显示名称（如：生产数据库服务器） */
    private String name;

    /** IP 或主机名（如：192.168.1.10） */
    private String host;

    /** 节点类型 */
    @Builder.Default
    private String type = "REMOTE";

    /** 描述信息 */
    private String description;

    /** 创建时间 */
    @Builder.Default
    private long createdAt = System.currentTimeMillis();
}