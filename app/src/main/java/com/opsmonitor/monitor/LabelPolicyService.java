package com.opsmonitor.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签策略服务 (10C P0-3)
 *
 * 强制校验所有进入系统的 metrics 必须包含规定标签。
 * 在 Exporter 注册、Target 写入、Remote Write 入口处调用。
 *
 * 必须标签（缺失则拒绝注册）：
 * - exporter_type
 * - managed_by
 *
 * 推荐标签（缺失则自动填充默认值）：
 * - server_id → "unknown"
 * - server_name → "unnamed"
 * - project → "default"
 * - service → "default"
 * - env → "prod"
 * - node → server_id
 */
@Slf4j
@Service
public class LabelPolicyService {

    /** 必须存在且非空的标签 */
    private static final Set<String> REQUIRED_LABELS = Set.of(
            "exporter_type", "managed_by"
    );

    /** 推荐标签及默认值 */
    private static final Map<String, String> RECOMMENDED_DEFAULTS = Map.of(
            "server_id", "unknown",
            "server_name", "unnamed",
            "project", "default",
            "service", "default",
            "env", "prod"
    );

    /**
     * 校验标签完整性
     * @return 错误消息列表（空=通过）
     */
    public List<String> validate(Map<String, String> labels) {
        List<String> errors = new ArrayList<>();
        if (labels == null) {
            errors.add("labels 不能为 null");
            return errors;
        }
        for (String required : REQUIRED_LABELS) {
            String value = labels.get(required);
            if (value == null || value.isBlank()) {
                errors.add("缺少必须标签: " + required);
            }
        }
        return errors;
    }

    /**
     * 校验并自动填充缺失的推荐标签
     * @return 填充后的标签（修改原 Map）
     */
    public Map<String, String> enforcePolicy(Map<String, String> labels) {
        if (labels == null) return Map.of();

        // 校验必须标签
        List<String> errors = validate(labels);
        if (!errors.isEmpty()) {
            log.warn("[LabelPolicy] 标签校验失败: {}", errors);
        }

        // 填充推荐标签默认值
        for (Map.Entry<String, String> entry : RECOMMENDED_DEFAULTS.entrySet()) {
            labels.putIfAbsent(entry.getKey(), entry.getValue());
        }

        // node 默认等于 server_id
        labels.putIfAbsent("node", labels.getOrDefault("server_id", "unknown"));

        return labels;
    }

    /**
     * 严格模式：缺少必须标签则抛异常
     */
    public void enforceStrict(Map<String, String> labels) {
        List<String> errors = validate(labels);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("标签策略违规: " + String.join(", ", errors));
        }
        enforcePolicy(labels);
    }
}