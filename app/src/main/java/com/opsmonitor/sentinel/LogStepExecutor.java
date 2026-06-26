package com.opsmonitor.sentinel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LOG 步骤执行器 — 仅记录日志，不执行实际操作
 *
 * OPTIMIZED: 继承 AbstractStepExecutor
 *   - 删除了原有 interpolate() 实现（22→0 行，已移至基类）
 *   - 删除了 execute() 样板代码（基类模板方法处理计时+异常）
 *   - 只保留 getType() + 核心业务逻辑 executeInternal()
 *   改造前：42 行 → 改造后：26 行（节省 16 行）
 */
@Slf4j
@Component
public class LogStepExecutor extends AbstractStepExecutor {

    @Override
    public String getType() { return "LOG"; }

    @Override
    protected ExecutionResult executeInternal(RunbookStep step,
                                              Map<String, Object> context,
                                              String resolvedCommand,
                                              int timeoutSeconds) {
        // OPTIMIZED: resolvedCommand 已由基类 interpolate() 完成变量替换，直接使用
        log.info("[Runbook][LOG] {} — {}", step.getName(), resolvedCommand);
        return ExecutionResult.ok(step.getName(), getType(), "📝 " + resolvedCommand, 0);
    }
}
