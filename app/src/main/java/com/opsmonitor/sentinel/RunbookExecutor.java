package com.opsmonitor.sentinel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Runbook 执行调度器
 *
 * 核心功能：
 *   1. 按步骤顺序执行 RunbookStep 列表
 *   2. failFast=true 时遇到失败立即停止
 *   3. 每步执行前记录日志，执行后记录结果
 *   4. 支持上下文变量注入（serverHost / exporterType 等）
 */
@Slf4j
@Service
public class RunbookExecutor {

    /** 所有 StepExecutor 实现，由 Spring 自动注入 */
    private final Map<String, StepExecutor> executorMap;

    public RunbookExecutor(List<StepExecutor> executors) {
        this.executorMap = executors.stream()
                .collect(Collectors.toMap(StepExecutor::getType, e -> e));
        log.info("[RunbookExecutor] 已加载执行器: {}", executorMap.keySet());
    }

    /**
     * 执行 Runbook 步骤列表
     *
     * @param steps   步骤列表
     * @param context 上下文变量（serverHost / serverId / exporterType 等）
     * @return 每步的执行结果
     */
    public RunbookExecution execute(String runbookName,
                                    List<RunbookStep> steps,
                                    Map<String, Object> context) {
        log.info("[RunbookExecutor] 开始执行 Runbook: {} ({} 步)", runbookName, steps.size());

        List<ExecutionResult> results = new ArrayList<>();
        boolean allSuccess = true;
        long start = System.currentTimeMillis();

        for (int i = 0; i < steps.size(); i++) {
            RunbookStep step = steps.get(i);
            log.info("[RunbookExecutor] 步骤 {}/{}: [{}] {}", i + 1, steps.size(), step.getType(), step.getName());

            StepExecutor executor = executorMap.get(step.getType());
            if (executor == null) {
                ExecutionResult err = ExecutionResult.fail(
                        step.getName(), step.getType(),
                        "无对应执行器，支持类型: " + executorMap.keySet(), 0);
                results.add(err);
                log.warn("[RunbookExecutor] 跳过步骤（无执行器）: type={}", step.getType());
                if (step.isFailFast()) { allSuccess = false; break; }
                continue;
            }

            ExecutionResult result = executor.execute(step, context);
            results.add(result);

            if (!result.isSuccess()) {
                log.warn("[RunbookExecutor] 步骤失败: {} — {}", step.getName(), result.getMessage());
                if (step.isFailFast()) {
                    allSuccess = false;
                    log.warn("[RunbookExecutor] failFast=true，中止后续步骤");
                    break;
                }
            } else {
                log.info("[RunbookExecutor] 步骤成功: {} ({}ms)", step.getName(), result.getDurationMs());
            }
        }

        long totalMs = System.currentTimeMillis() - start;
        log.info("[RunbookExecutor] Runbook 执行完毕: {} 步, 总耗时 {}ms, 整体结果: {}",
                results.size(), totalMs, allSuccess ? "SUCCESS" : "FAILED");

        return new RunbookExecution(runbookName, results, allSuccess, totalMs);
    }

    /** 简化版：无 runbookName 时使用 */
    public RunbookExecution execute(List<RunbookStep> steps, Map<String, Object> context) {
        return execute("manual", steps, context);
    }

    // ── 执行汇总结果 ───────────────────────────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class RunbookExecution {
        private String              runbookName;
        private List<ExecutionResult> stepResults;
        private boolean             overallSuccess;
        private long                totalDurationMs;

        public int successCount() {
            return (int) stepResults.stream().filter(ExecutionResult::isSuccess).count();
        }
        public int failureCount() {
            return stepResults.size() - successCount();
        }
    }
}
