package com.opsmonitor.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 通知渠道模型 (11A)
 *
 * 支持类型：
 *   DINGTALK   — 钉钉机器人 Webhook
 *   FEISHU     — 飞书机器人 Webhook
 *   WECOM      — 企业微信机器人 Webhook
 *   WEBHOOK    — 通用 HTTP Webhook（POST JSON）
 *   SLACK      — Slack Incoming Webhook
 *
 * 触发策略：
 *   ALL        — 所有告警
 *   CRITICAL   — 仅 critical 级别
 *   CUSTOM     — 自定义 alertName/serverName 过滤
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationChannel {

    private String id;

    /** 渠道显示名称，如"生产环境告警群" */
    private String name;

    /** 渠道类型: DINGTALK / FEISHU / WECOM / WEBHOOK / SLACK */
    private String type;

    /** Webhook URL（所有类型通用） */
    private String webhookUrl;

    /** 是否启用 */
    @Builder.Default
    private boolean enabled = true;

    /**
     * 触发策略: ALL / CRITICAL / CUSTOM
     * CUSTOM 时使用 filterAlertName / filterServerName
     */
    @Builder.Default
    private String triggerPolicy = "ALL";

    /** CUSTOM 策略: 只发指定 alertName（null=不过滤） */
    private String filterAlertName;

    /** CUSTOM 策略: 只发指定 serverName（null=不过滤） */
    private String filterServerName;

    /**
     * 告警时发送 (true=FIRING, false=仅RESOLVED)
     * 默认两种都发
     */
    @Builder.Default
    private boolean notifyOnFiring = true;

    @Builder.Default
    private boolean notifyOnResolved = true;

    /** 扩展字段（供自定义模板使用） */
    private Map<String, String> extra;

    /** 创建时间 */
    private long createdAt;

    /** 发送统计（内存，不持久化） */
    private transient long sentCount;
    private transient long failCount;
    private transient long lastSentAt;
}