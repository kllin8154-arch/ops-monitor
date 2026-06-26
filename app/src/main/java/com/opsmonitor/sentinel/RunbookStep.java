package com.opsmonitor.sentinel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runbook 步骤定义
 *
 * type 可选值：
 *   HTTP   — 发送 HTTP 请求（GET/POST），command = URL
 *   SCRIPT — 在本机执行 shell 命令，command = shell 语句
 *   SSH    — 远程 SSH 执行，command = shell 语句，需 context 含 host/user/password
 *   LOG    — 仅记录日志，不执行实际操作（用于审计步骤）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunbookStep {

    /** 步骤名称（人类可读） */
    private String name;

    /** 执行类型: HTTP / SCRIPT / SSH / LOG */
    private String type;

    /**
     * 执行内容：
     *   HTTP   → URL（支持 GET，如需 POST 则 command = "POST:url:body"）
     *   SCRIPT → shell 命令字符串
     *   SSH    → shell 命令字符串（在远程执行）
     *   LOG    → 日志内容（直接记录）
     */
    private String command;

    /** 步骤超时（秒），0 = 使用全局默认 10s */
    @Builder.Default
    private int timeoutSeconds = 10;

    /** 失败时是否中断后续步骤（默认 true） */
    @Builder.Default
    private boolean failFast = true;

    /** 可选：步骤说明/注释 */
    private String description;
}
