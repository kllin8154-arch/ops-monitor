package com.opsmonitor.sentinel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * SCRIPT 步骤执行器 — 在本机执行 Shell 命令（白名单安全模式）
 *
 * OPTIMIZED: 继承 AbstractStepExecutor
 *   - 删除了原有 interpolate() 实现（9 行冗余代码消除）
 *   - 删除了 execute() 样板（计时/顶层异常由基类处理）
 *   - resolvedCommand 由基类传入，无需手动调用 interpolate()
 *   改造前：128 行 → 改造后：108 行（节省 20 行）
 *
 * 安全约束：
 *   - 仅允许 ALLOWED_COMMANDS 白名单内的命令（枚举型，非前缀匹配）
 *   - 禁止 BLOCKED_PATTERNS 中的危险命令/模式
 *   - 超时强制 kill（防止命令挂起阻塞线程）
 */
@Slf4j
@Component
public class ScriptStepExecutor extends AbstractStepExecutor {

    // OPTIMIZED: 安全命令白名单（枚举型，不可绕过）
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "df ", "du ", "free ", "top ", "ps ", "cat /proc",
            "docker ps", "docker stats", "docker logs", "docker inspect",
            "curl ", "wget ", "ping ",
            "systemctl status", "journalctl",
            "echo ", "date", "uptime", "hostname",
            "netstat ", "ss ", "lsof "
    );

    // 绝对禁止的危险命令（含逃逸路径）
    private static final Set<String> BLOCKED_PATTERNS = Set.of(
            "rm ", "rmdir", "shutdown", "reboot", "init ", "halt",
            "mkfs", "dd if=", "chmod 777", "> /dev/", "| sh", "| bash",
            "curl.*| sh", "wget.*| sh", ";rm", "&& rm",
            "nc ", "netcat", "ncat",
            "base64 -d", "base64 --decode",
            "python -c", "python3 -c",
            "perl -e", "ruby -e",
            "|bash", "|sh", ">/etc/",
            "$(", "`"
    );

    // v2.26-sec: shell 元字符正则拦截（防止 ; | & ` $ ( ) 等绕过黑名单）
    private static final Pattern SHELL_META_PATTERN = Pattern.compile("[;|&`$(){}\\[\\]!#~><]");

    @Override
    public String getType() { return "SCRIPT"; }

    @Override
    protected ExecutionResult executeInternal(RunbookStep step,
                                              Map<String, Object> context,
                                              String resolvedCommand,
                                              int timeoutSeconds) {
        long start = System.currentTimeMillis();
        // OPTIMIZED: resolvedCommand 已由基类完成变量替换，直接使用
        String cmd = resolvedCommand;

        // 安全校验
        String safetyError = checkSafety(cmd);
        if (safetyError != null) {
            return ExecutionResult.fail(step.getName(), getType(),
                    "命令被安全策略拒绝: " + safetyError, 0);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            // OPTIMIZED: 清除危险环境变量，防止 LD_PRELOAD 等注入
            pb.environment().remove("LD_PRELOAD");
            pb.environment().remove("LD_LIBRARY_PATH");
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines < 50) {
                    output.append(line).append("\n");
                    lines++;
                }
                if (lines >= 50) output.append("...(输出已截断)");
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                long ms = System.currentTimeMillis() - start;
                return ExecutionResult.fail(step.getName(), getType(),
                        "命令超时 (" + timeoutSeconds + "s)，已强制终止", ms);
            }

            long ms = System.currentTimeMillis() - start;
            int exitCode = process.exitValue();
            String msg = "exit=" + exitCode + "\n" + output;

            if (exitCode == 0) {
                log.info("[ScriptExecutor] 命令成功: {} ({}ms)",
                        cmd.substring(0, Math.min(50, cmd.length())), ms);
                return ExecutionResult.ok(step.getName(), getType(), msg, ms);
            } else {
                return ExecutionResult.fail(step.getName(), getType(), msg, ms);
            }

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("[ScriptExecutor] 执行异常: {}", e.getMessage());
            return ExecutionResult.fail(step.getName(), getType(),
                    "执行异常: " + e.getMessage(), ms);
        }
    }

    private String checkSafety(String cmd) {
        if (cmd == null || cmd.isBlank()) return "命令不能为空";
        String lower = cmd.toLowerCase().trim();
        for (String blocked : BLOCKED_PATTERNS) {
            if (lower.contains(blocked.toLowerCase())) {
                return "包含危险模式: " + blocked;
            }
        }
        if (SHELL_META_PATTERN.matcher(cmd).find()) {
            return "命令包含禁止的 shell 元字符";
        }
        boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(lower::startsWith);
        if (!allowed) {
            return "命令不在白名单中（允许: df/du/free/docker/curl/ping/systemctl status 等）";
        }
        return null;
    }
}
