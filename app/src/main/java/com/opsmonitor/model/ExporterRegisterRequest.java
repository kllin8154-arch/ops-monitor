package com.opsmonitor.model;

import lombok.Data;

import java.util.Map;

/**
 * 注册 Exporter 的请求参数（9C 企业级版）
 *
 * 新增：project / service / environment 三级标签维度
 */
@Data
public class ExporterRegisterRequest {

    /** Exporter 类型 */
    private String type;

    /** 所属服务器节点 ID（可选，默认 local） */
    private String serverId;

    /** 目标服务地址 */
    private String targetAddress;

    /** 自定义显示名称（可选） */
    private String displayName;

    /** 宿主机映射端口（可选） */
    private Integer metricsPort;

    /** 项目名称（如 order-system） */
    private String project;

    /** 服务名称（如 order-api） */
    private String service;

    /** 环境（如 prod / staging / dev） */
    private String environment;

    /** 环境变量覆盖（可选） */
    private Map<String, String> env;
}