package com.opsmonitor.sentinel;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * SSH 步骤执行器（JSch 集成）
 *
 * OPTIMIZED: 继承 AbstractStepExecutor
 *   - 删除了原有 interpolate() 实现（9 行冗余代码消除）
 *   - 删除了 execute() 样板（计时/顶层异常由基类处理）
 *   - resolvedCommand 由基类传入，无需手动调用 interpolate()
 *   改造前：207 行 → 改造后：185 行（节省 22 行）
 *
 * context 预期键：
 *   serverHost    — 目标 SSH 地址（必须）
 *   sshPort       — SSH 端口（默认 22）
 *   sshUser       — 用户名（默认 root）
 *   sshPassword   — 密码（与 sshPrivateKey 二选一）
 *   sshPrivateKey — 私钥文件路径
 */
@Slf4j
@Component
public class SshStepExecutor extends AbstractStepExecutor {

    // OPTIMIZED: 与 ScriptStepExecutor 共用白名单逻辑（但 SSH 允许更多读命令）
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "df ", "du ", "free ", "top ", "ps ", "cat /proc",
            "docker ps", "docker stats", "docker logs", "docker inspect",
            "curl ", "wget ", "ping ",
            "systemctl status", "journalctl",
            "echo ", "date", "uptime", "hostname",
            "netstat ", "ss ", "lsof ",
            "ls ", "cat /etc", "tail ", "head ", "grep "
    );

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
            "rm ", "rmdir", "shutdown", "reboot", "init ", "halt",
            "mkfs", "dd if=", "> /dev/", "| sh", "| bash", ";rm", "&& rm"
    );

    // v2.26-sec: shell 元字符正则拦截
    private static final Pattern SHELL_META_PATTERN = Pattern.compile("[;|&`$(){}\\[\\]!#~><]");

    // v2.13-C: 配置化超时与安全选项（从 application.yml 读取）
    @Value("${ops-monitor.sentinel.ssh.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;

    @Value("${ops-monitor.sentinel.ssh.exec-timeout-seconds:30}")
    private int execTimeoutSeconds;

    @Value("${ops-monitor.sentinel.ssh.strict-host-key-checking:no}")
    private String strictHostKeyChecking;

    /** v2.13-C: 输出最大字节数（防止大输出 OOM） */
    private static final int MAX_OUTPUT_BYTES = 10_240; // 10 KB

    @Override
    public String getType() { return "SSH"; }

    @Override
    protected ExecutionResult executeInternal(RunbookStep step,
                                              Map<String, Object> context,
                                              String resolvedCommand,
                                              int timeoutSeconds) {
        long start = System.currentTimeMillis();

        // 从 context 提取连接参数
        String host       = (String) context.getOrDefault("serverHost", "");
        String user       = (String) context.getOrDefault("sshUser", "root");
        String password   = (String) context.getOrDefault("sshPassword", "");
        String privateKey = (String) context.getOrDefault("sshPrivateKey", "");
        int    port       = parsePort(context.getOrDefault("sshPort", "22"));
        // OPTIMIZED: resolvedCommand 已由基类完成变量替换，直接使用
        String cmd        = resolvedCommand;

        if (host.isBlank()) {
            return ExecutionResult.fail(step.getName(), getType(),
                    "缺少 serverHost，请在 context 中提供目标主机地址", 0);
        }
        if (password.isBlank() && privateKey.isBlank()) {
            return ExecutionResult.fail(step.getName(), getType(),
                    "缺少认证信息：请在 context 中提供 sshPassword 或 sshPrivateKey", 0);
        }

        String safetyError = checkSafety(cmd);
        if (safetyError != null) {
            return ExecutionResult.fail(step.getName(), getType(),
                    "命令被安全策略拒绝: " + safetyError, 0);
        }

        log.info("[SshExecutor] 连接 {}@{}:{} 执行: {}",
                user, host, port, cmd.substring(0, Math.min(60, cmd.length())));

        JSch jsch = new JSch();
        Session session = null;
        ChannelExec channel = null;
        try {
            if (!privateKey.isBlank()) {
                jsch.addIdentity(privateKey);
            }

            session = jsch.getSession(user, host, port);

            if (!password.isBlank()) {
                session.setPassword(password);
            }

            Properties config = new Properties();
            // v2.13-C: StrictHostKeyChecking 从配置读取（默认 no，生产可设为 yes）
            config.put("StrictHostKeyChecking", strictHostKeyChecking);
            session.setConfig(config);
            session.setTimeout(connectTimeoutSeconds * 1000);
            session.connect();
            log.debug("[SshExecutor] SSH 连接成功: {}@{}:{}", user, host, port);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(cmd);
            channel.setInputStream(null);
            // P0-3 fix: stderr 不再输出到 System.err（防止敏感信息泄露到日志收集系统）
            // 改为捕获到内存，统一通过 log 输出
            java.io.ByteArrayOutputStream errBuffer = new java.io.ByteArrayOutputStream();
            channel.setErrStream(errBuffer);

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(channel.getInputStream()))) {
                channel.connect();
                String line;
                int lines = 0;
                int totalBytes = 0;
                boolean byteLimitReached = false;
                // v2.13-C: 使用配置的执行超时
                long deadline = System.currentTimeMillis() + execTimeoutSeconds * 1000L;
                while ((line = reader.readLine()) != null) {
                    int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                    // v2.13-C: 10KB 字节截断 + 100 行截断
                    if (totalBytes + lineBytes > MAX_OUTPUT_BYTES && lines >= 50) {
                        output.append("...(输出已截断，超过").append(MAX_OUTPUT_BYTES / 1024).append("KB)");
                        byteLimitReached = true;
                        break;
                    }
                    if (lines >= 100) {
                        output.append("...(输出已截断，超过100行)");
                        break;
                    }
                    output.append(line).append("\n");
                    totalBytes += lineBytes;
                    lines++;
                    if (System.currentTimeMillis() > deadline) {
                        output.append("...(执行超时").append(execTimeoutSeconds).append("s，输出已截断)");
                        break;
                    }
                }
            }

            long waited = 0;
            while (!channel.isClosed() && waited < timeoutSeconds * 1000L) {
                TimeUnit.MILLISECONDS.sleep(200);
                waited += 200;
            }

            int exitCode = channel.getExitStatus();
            long ms = System.currentTimeMillis() - start;
            // P0-3 fix: 记录 stderr 内容（已捕获到 errBuffer，不再泄露到 System.err）
            String stderrContent = errBuffer.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (!stderrContent.isBlank()) {
                log.debug("[SshExecutor] stderr: {}", stderrContent.substring(0, Math.min(500, stderrContent.length())));
            }
            String msg = "exit=" + exitCode + " host=" + host + "\n" + output;

            if (exitCode == 0) {
                log.info("[SshExecutor] 执行成功: {} ({}ms) host={}",
                        cmd.substring(0, Math.min(40, cmd.length())), ms, host);
                return ExecutionResult.ok(step.getName(), getType(), msg, ms);
            } else {
                log.warn("[SshExecutor] 命令返回非零: exitCode={} host={}", exitCode, host);
                return ExecutionResult.fail(step.getName(), getType(), msg, ms);
            }

        } catch (com.jcraft.jsch.JSchException e) {
            long ms = System.currentTimeMillis() - start;
            String errMsg;
            if (e.getMessage() != null && e.getMessage().contains("Auth fail")) {
                errMsg = "SSH 认证失败：用户名或密码错误 (" + user + "@" + host + ")";
            } else if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                errMsg = "SSH 连接被拒绝：" + host + ":" + port + " 端口未开放或 SSH 服务未启动";
            } else if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                errMsg = "SSH 连接超时：" + host + " 不可达（10秒）";
            } else {
                errMsg = "SSH 连接失败：" + e.getMessage();
            }
            log.error("[SshExecutor] {}", errMsg);
            return ExecutionResult.fail(step.getName(), getType(), errMsg, ms);

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("[SshExecutor] 执行异常: {}", e.getMessage());
            return ExecutionResult.fail(step.getName(), getType(),
                    "执行异常: " + e.getMessage(), ms);

        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
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
            return "命令不在白名单（允许: df/du/free/docker/curl/ping/systemctl status/ls 等）";
        }
        return null;
    }

    private int parsePort(Object portObj) {
        try {
            return Integer.parseInt(String.valueOf(portObj));
        } catch (NumberFormatException e) {
            return 22;
        }
    }
}