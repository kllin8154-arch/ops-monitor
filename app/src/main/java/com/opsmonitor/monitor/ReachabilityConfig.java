package com.opsmonitor.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 主机可达性探测配置 (BUG-HOST-DOWN 阶段 C 修复)
 *
 * 独立的 @ConfigurationProperties 类，不侵入 OpsMonitorProperties。
 * 配置前缀：ops-monitor.reachability
 *
 * application.yml 示例：
 *
 *   ops-monitor:
 *     reachability:
 *       enabled: true
 *       probe-ports: [22, 3389, 80, 8080, 443]
 *       connect-timeout-ms: 2000
 *       failure-threshold: 2
 *       incident-on-down: true
 *       incident-severity: P1
 *
 * 遵循 AGENT_RULES：
 *   - 不修改 OpsMonitorProperties（最小侵入）
 *   - 默认值与硬编码版本一致，向后兼容
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-monitor.reachability")
public class ReachabilityConfig {

    /**
     * 是否启用主机活性探测（默认 true）
     * 设为 false 时 RemoteHostReachabilityProbe 不工作，所有主机视为可达
     */
    private boolean enabled = true;

    /**
     * 探测端口优先级列表，任一可达即认为主机在线
     * 默认包含 SSH(22) / RDP(3389) / HTTP(80) / 备用 HTTP(8080) / HTTPS(443)
     */
    private List<Integer> probePorts = Arrays.asList(22, 3389, 80, 8080, 443);

    /**
     * 单次 TCP connect 超时（毫秒）
     */
    private int connectTimeoutMs = 2000;

    /**
     * 连续失败多少次才判定为不可达（防瞬时网络抖动）
     */
    private int failureThreshold = 2;

    /**
     * 主机首次不可达时是否自动创建 Sentinel Incident
     */
    private boolean incidentOnDown = true;

    /**
     * 自动创建的 Incident 严重等级（P0/P1/P2）
     * 默认 P1（严重降级，但不致命）
     */
    private String incidentSeverity = "P1";

    /**
     * 探测周期（毫秒），与 RemoteHostReachabilityProbe 的 @Scheduled 对齐
     * 注意：修改此值不会改变 @Scheduled fixedDelay（注解为编译期常量）
     * 此字段仅用于文档说明，实际周期固定 30 秒
     */
    private long probeIntervalMs = 30_000;
}