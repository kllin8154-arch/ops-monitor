package com.opsmonitor.sentinel;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Runbook 步骤执行器抽象基类
 *
 * OPTIMIZED: 统一提取 4 个执行器中各自重复实现的公共能力：
 *   - interpolate()     模板变量替换（原各自重复 9 行，共 36 行冗余）
 *   - resolveTimeout()  超时解析（原各自散落，现统一默认值）
 *   - execute()         模板方法（含顶层异常兜底，防止执行器崩溃传播）
 *   - success/failure() 结果快捷构建
 *
 * 子类只需实现：
 *   - getType()             返回执行器类型字符串
 *   - executeInternal(...)  实际业务逻辑
 */
@Slf4j
public abstract class AbstractStepExecutor implements StepExecutor {

    // OPTIMIZED: 统一默认超时，原各文件分散硬编码（10/15/30）
    protected static final int DEFAULT_TIMEOUT_SECONDS = 15;

    /**
     * OPTIMIZED: 模板方法 — 基类统一处理计时 + 顶层异常，子类只写业务
     *
     * 子类禁止覆写此方法（final），只实现 executeInternal()
     */
    @Override
    public final ExecutionResult execute(RunbookStep step, Map<String, Object> context) {
        long start = System.currentTimeMillis();
        // OPTIMIZED: 变量替换在基类完成，子类直接使用 resolvedCommand
        String resolvedCommand = interpolate(step.getCommand(), context);
        int timeout = resolveTimeout(step);
        try {
            return executeInternal(step, context, resolvedCommand, timeout);
        } catch (Exception e) {
            // OPTIMIZED: 顶层兜底，防止单个执行器异常中断整个 Runbook
            long ms = System.currentTimeMillis() - start;
            log.error("[{}Executor] 未预期异常: step={} error={}",
                    getType(), step.getName(), e.getMessage(), e);
            return ExecutionResult.fail(step.getName(), getType(),
                    "执行器内部错误: " + e.getMessage(), ms);
        }
    }

    /**
     * 子类实现具体执行逻辑
     *
     * @param step            步骤定义（含 name/type/failFast 等属性）
     * @param context         原始上下文 Map（子类可读取额外参数）
     * @param resolvedCommand 已完成 ${key} 变量替换的命令字符串
     * @param timeoutSeconds  已解析的超时秒数（含默认值处理）
     */
    protected abstract ExecutionResult executeInternal(
            RunbookStep step,
            Map<String, Object> context,
            String resolvedCommand,
            int timeoutSeconds);

    // ==================== 公共工具方法（子类可直接调用）====================

    /**
     * OPTIMIZED: 模板变量替换 — 原在 4 个执行器中各自实现，现统一至此
     *
     * 将 "${key}" 替换为 context 中对应的值。
     * 如 "df -h ${serverHost}" + {"serverHost":"10.0.0.1"} → "df -h 10.0.0.1"
     */
    protected final String interpolate(String template, Map<String, Object> ctx) {
        if (template == null) return null;
        if (ctx == null || ctx.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : ctx.entrySet()) {
            result = result.replace(
                    "${" + entry.getKey() + "}",
                    entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
        }
        return result;
    }

    /**
     * OPTIMIZED: 超时解析 — 统一处理 step.timeoutSeconds <= 0 的默认值
     */
    protected final int resolveTimeout(RunbookStep step) {
        return step.getTimeoutSeconds() > 0 ? step.getTimeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
    }

    /** 构建成功结果（含自动计时） */
    protected final ExecutionResult success(RunbookStep step, String message, long startMs) {
        return ExecutionResult.ok(step.getName(), getType(), message,
                System.currentTimeMillis() - startMs);
    }

    /** 构建失败结果（含自动计时） */
    protected final ExecutionResult failure(RunbookStep step, String message, long startMs) {
        return ExecutionResult.fail(step.getName(), getType(), message,
                System.currentTimeMillis() - startMs);
    }
}
