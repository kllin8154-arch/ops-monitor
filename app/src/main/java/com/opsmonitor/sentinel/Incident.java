package com.opsmonitor.sentinel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Incident（故障事件）— 内存持久化版本
 *
 * 生命周期: OPEN → INVESTIGATING → RESOLVED / CLOSED
 *
 * v2.12 增强：
 *   - lastSeenTime：去重复诊断时更新"最后一次触发时间"（便于运维判断告警活跃度）
 *   - indicatorSnapshot：诊断时的 7 项指标快照（便于事后分析）
 *   - affectedExporters：诊断时发现的异常 Exporter ID 列表
 *
 * 注：无 JPA 依赖，使用 JSON 文件持久化（IncidentService 负责）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Incident {

    /** 唯一 ID（UUID） */
    private String id;

    /** 关联服务器 ID */
    private String serverId;

    /** 关联服务器名称 */
    private String serverName;

    /** 故障指纹 ID（如 DISK_IO_BOUND / OOM_RISK） */
    private String fingerprint;

    /** 故障名称（中文，如 "磁盘IO瓶颈"） */
    private String faultName;

    /** 严重级别: P0 / P1 / P2 / P3 */
    private String severity;

    /** 状态: OPEN / INVESTIGATING / RESOLVED / CLOSED */
    @Builder.Default
    private String status = "OPEN";

    /** Impact Score（越高越优先处理） */
    private double impactScore;

    /** 根因描述 */
    private String rootCause;

    /** Runbook 步骤（可直接执行） */
    private List<com.opsmonitor.sentinel.RunbookStep> runbookSteps;

    /** Runbook 执行历史 */
    private List<com.opsmonitor.sentinel.ExecutionResult> executionHistory;

    /** 诊断置信度（0-100） */
    private double confidence;

    /** 开启时间 */
    @Builder.Default
    private long startTime = System.currentTimeMillis();

    /** 解决时间（RESOLVED 后设置） */
    private long endTime;

    /** 持续时长（毫秒，RESOLVED 后计算） */
    public long getDurationMs() {
        return endTime > 0 ? endTime - startTime : System.currentTimeMillis() - startTime;
    }

    /** 操作员（谁触发的 Runbook 执行） */
    private String operator;

    /** 备注 */
    private String notes;

    // ── v2.12 新增字段 ──────────────────────────────────────────

    /**
     * 最后一次诊断触发时间（毫秒时间戳）
     * 去重场景：同 fingerprint + serverId 的告警反复触发时，
     * open() 不会重复创建 Incident，而是更新此字段。
     * 运维人员可据此判断告警是否仍在活跃。
     * 默认值 0 表示尚未设置（兼容旧数据反序列化）。
     */
    private long lastSeenTime;

    /**
     * 诊断时的指标快照（key=指标名, value=指标值）
     * 例如：{"cpu_total":87.3, "memory_used":92.1, "exporter_up_rate":0.75}
     * 供前端展示和事后分析。
     * 旧数据反序列化时为 null（Jackson 默认行为，向后兼容）。
     */
    private Map<String, Double> indicatorSnapshot;

    /**
     * 诊断时发现的异常 Exporter ID 列表
     * 例如：["local-node-9100", "192-168-10-103-nginx-9113"]
     * 旧数据反序列化时为 null（向后兼容）。
     */
    private List<String> affectedExporters;
}
