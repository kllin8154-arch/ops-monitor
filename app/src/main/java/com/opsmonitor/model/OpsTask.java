package com.opsmonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运维任务模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsTask {

    private String id;

    /** 任务名称 */
    private String name;

    /** 任务类型: SCRIPT / DOCKER / HEALTHCHECK */
    private String type;

    /** 目标服务器 ID（可选，空=本机） */
    private String targetServerId;

    /** 任务内容（脚本 / docker 命令 / URL） */
    private String payload;

    /** 状态: PENDING / RUNNING / SUCCESS / FAILED */
    @Builder.Default
    private String status = "PENDING";

    /** 执行结果 */
    private String result;

    /** 创建人 */
    private String createdBy;

    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    private long startedAt;
    private long finishedAt;
}