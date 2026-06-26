package com.opsmonitor.sentinel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 单个 RunbookStep 执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {

    /** 步骤名称 */
    private String stepName;

    /** 执行类型 */
    private String type;

    /** 是否成功 */
    private boolean success;

    /** 输出消息（成功时为返回内容，失败时为错误描述） */
    private String message;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 执行时间戳 */
    @Builder.Default
    private Instant executedAt = Instant.now();

    /** 快捷构造：成功 */
    public static ExecutionResult ok(String stepName, String type, String message, long ms) {
        return ExecutionResult.builder()
                .stepName(stepName).type(type)
                .success(true).message(message).durationMs(ms)
                .build();
    }

    /** 快捷构造：失败 */
    public static ExecutionResult fail(String stepName, String type, String message, long ms) {
        return ExecutionResult.builder()
                .stepName(stepName).type(type)
                .success(false).message(message).durationMs(ms)
                .build();
    }
}
