package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 能力声明 (10C P0-4)
 *
 * Agent 注册时上报自身能力，Control Plane 据此决策：
 * - 是否需要升级
 * - 支持哪些 Exporter
 * - 是否支持 Remote Write
 * - 是否支持服务发现
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCapability {

    /** Agent 版本 (语义化: 2.0.0) */
    private String agentVersion;

    /** 支持的协议版本 */
    @Builder.Default
    private String protocolVersion = "v1";

    /** 是否支持 Remote Write */
    @Builder.Default
    private boolean remoteWriteEnabled = true;

    /** 是否支持服务发现 */
    @Builder.Default
    private boolean discoveryEnabled = true;

    /** 是否支持 Exporter 管理 */
    @Builder.Default
    private boolean exporterManagementEnabled = true;

    /** 支持的 Exporter 类型 */
    private List<String> supportedExporterTypes;

    /** Control Plane 返回：是否需要升级 */
    @Builder.Default
    private boolean upgradeRequired = false;

    /** Control Plane 返回：最低要求版本 */
    private String minimumVersion;
}