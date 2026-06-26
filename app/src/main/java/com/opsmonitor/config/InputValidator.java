package com.opsmonitor.config;

import java.util.regex.Pattern;

/**
 * 输入校验工具 (P1-3 + P1-4)
 *
 * P1-3: Docker 容器 ID 格式校验（12-64 位 hex）
 * P1-4: Exporter targetAddress 格式校验
 * 通用：路径遍历防护 / XSS 过滤
 */
public final class InputValidator {

    private InputValidator() {}

    /** Docker 容器 ID：12-64 位十六进制 或 容器名称（字母数字下划线横线） */
    private static final Pattern CONTAINER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{1,63}$");

    /** IP:Port 格式 */
    private static final Pattern IP_PORT_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9.\\-_]+:\\d{1,5}$"
    );

    /** URL 格式（含协议）
     * FIX: 新增 postgresql:// 协议支持（postgres-exporter 标准连接串格式）
     *      新增 jdbc: 前缀支持（未来扩展）
     *      字符类扩展：加入 % 和 + 支持 URL 编码的密码
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?|redis|mysql|postgres(?:ql)?|mongodb|oracle|jdbc:[a-z]+)://[a-zA-Z0-9.\\-_:/@?&=%+]+$"
    );

    /** 路径遍历字符 */
    private static final Pattern PATH_TRAVERSAL = Pattern.compile("\\.\\./|\\.\\.\\\\");

    /**
     * IPv4 地址格式（每段 0-255）
     * 匹配：192.168.1.1 / 10.0.0.1 / 127.0.0.1
     */
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    );

    /**
     * 合法主机名/域名格式
     * 匹配：hostname / host.domain.com / host-01.local
     */
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$"
    );

    /**
     * P1-3: 校验 Docker 容器 ID / 名称
     */
    public static void validateContainerId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("容器 ID 不能为空");
        }
        if (!CONTAINER_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("容器 ID 格式无效");
        }
    }

    /**
     * P1-4: 校验 Exporter targetAddress
     * 接受：IP:port / URL格式 / host.docker.internal:port
     */
    public static void validateTargetAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("目标地址不能为空");
        }

        String trimmed = address.trim();

        // 路径遍历检测
        if (PATH_TRAVERSAL.matcher(trimmed).find()) {
            throw new IllegalArgumentException("地址包含非法字符");
        }

        // 必须匹配 IP:Port 或 URL 格式
        if (!IP_PORT_PATTERN.matcher(trimmed).matches() && !URL_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("地址格式无效，示例: redis://host.docker.internal:6379 或 192.168.1.10:9121");
        }
    }

    /**
     * 通用：清理可能的 XSS 字符
     */
    // v2.26-sec: & 必须最先处理，与 ApiSafetyFilter.sanitize 保持一致
    public static String sanitize(String input) {
        if (input == null) return null;
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * BUG-1修复：校验服务器 host 字段必须是合法的 IPv4 地址或主机名
     * 防止注入任意字符串（如 javascript URL、SQL 注入等）
     */
    public static void validateServerHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("服务器地址不能为空");
        }
        String trimmed = host.trim();
        if (trimmed.length() > 253) {
            throw new IllegalArgumentException("服务器地址过长（最大 253 字符）");
        }
        // 路径遍历检测
        if (PATH_TRAVERSAL.matcher(trimmed).find()) {
            throw new IllegalArgumentException("服务器地址包含非法字符");
        }
        // 必须是 IPv4 或合法主机名（不允许协议前缀、端口、路径等）
        boolean isIpv4 = IPV4_PATTERN.matcher(trimmed).matches();
        boolean isHostname = HOSTNAME_PATTERN.matcher(trimmed).matches();
        if (!isIpv4 && !isHostname) {
            throw new IllegalArgumentException(
                    "服务器地址格式无效，请输入合法的 IPv4 地址（如 192.168.1.10）或主机名（如 myserver.local）");
        }
        // BUG-A 修复：纯主机名（无点）如 dsafd 无法被 DNS 解析，必须拒绝
        // 仅允许 IPv4 地址（天然含点）或包含至少一个点的完整域名
        if (isHostname && !trimmed.contains(".")) {
            throw new IllegalArgumentException(
                    "服务器地址无效（纯主机名如 dsafd 无法解析）。请输入合法的 IPv4 地址（如 192.168.1.10）或完整域名（如 myserver.local）");
        }
        // 明确拒绝 0.x.x.x 无效地址
        if (trimmed.startsWith("0.")) {
            throw new IllegalArgumentException("服务器地址无效（0.x.x.x 不允许）");
        }
    }

    /**
     * 校验端口范围
     */
    public static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口号范围: 1-65535");
        }
    }
}