package com.opsmonitor.sentinel;

import java.util.Map;

/**
 * Runbook 步骤执行器接口
 * 每种 type 对应一个实现（HTTP / SCRIPT / SSH / LOG）
 */
public interface StepExecutor {

    /** 返回此执行器负责的 type 字符串，如 "HTTP" */
    String getType();

    /**
     * 执行步骤
     *
     * @param step    步骤定义
     * @param context 上下文变量（serverId / serverHost / exporterType 等）
     * @return 执行结果
     */
    ExecutionResult execute(RunbookStep step, Map<String, Object> context);
}
