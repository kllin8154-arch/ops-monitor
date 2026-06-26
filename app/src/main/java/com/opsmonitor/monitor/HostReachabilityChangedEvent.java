package com.opsmonitor.monitor;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 主机可达性状态翻转事件 (v2.11 补充 hostIp 字段)
 *
 * v2.11 FIX-BRIDGE-2: 新增 hostIp 字段，让 ReachabilityIncidentBridge
 * 能在 rootCause 中写入具体 IP 地址，运维不再看到"未知IP"。
 *
 * 向后兼容：旧的 6 参数构造器保留，新增 7 参数构造器（含 hostIp）。
 */
@Getter
public class HostReachabilityChangedEvent extends ApplicationEvent {

    public enum SourceType { HOST, AGENT }

    private final SourceType sourceType;
    private final String serverId;
    private final String serverName;
    private final boolean reachable;
    private final String reason;
    /** v2.11: 主机 IP 地址（用于在 Incident.rootCause 中显示具体 IP） */
    private final String hostIp;

    /** v2.11 新构造器（含 hostIp） */
    public HostReachabilityChangedEvent(Object source,
                                        SourceType sourceType,
                                        String serverId,
                                        String serverName,
                                        boolean reachable,
                                        String reason,
                                        String hostIp) {
        super(source);
        this.sourceType = sourceType;
        this.serverId = serverId;
        this.serverName = serverName;
        this.reachable = reachable;
        this.reason = reason;
        this.hostIp = hostIp;
    }

    /** 向后兼容旧构造器（hostIp=null） */
    public HostReachabilityChangedEvent(Object source,
                                        SourceType sourceType,
                                        String serverId,
                                        String serverName,
                                        boolean reachable,
                                        String reason) {
        this(source, sourceType, serverId, serverName, reachable, reason, null);
    }

    @Override
    public String toString() {
        return String.format(
                "HostReachabilityChangedEvent[type=%s, serverId=%s, host=%s, reachable=%s]",
                sourceType, serverId, hostIp, reachable);
    }
}