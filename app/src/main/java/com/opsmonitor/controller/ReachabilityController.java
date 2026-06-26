package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.monitor.ExporterReconciler;
import com.opsmonitor.monitor.RemoteHostReachabilityProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主机可达性 & 对账状态 API (BUG-HOST-DOWN 修复)
 *
 * 前端可通过此端点直接查询：
 *   - GET  /api/reachability          所有服务器可达性快照
 *   - POST /api/reachability/probe    手动触发一次探测
 *   - GET  /api/reachability/status   对账 + 探测综合状态
 *
 * 说明：仅提供只读 + 无害的手动触发接口，无需 RBAC 写权限。
 *       如果需要限制访问，可后续加 @RequirePermission。
 */
@RestController
@RequestMapping("/api/reachability")
@RequiredArgsConstructor
public class ReachabilityController {

    private final RemoteHostReachabilityProbe probe;
    private final ExporterReconciler reconciler;

    /**
     * 获取所有服务器的可达性快照
     *
     * 返回示例：
     *   {
     *     "192-168-10-103": false,
     *     "local": true
     *   }
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listReachability() {
        Map<String, Boolean> snapshot = probe.getAllReachability();
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> e : snapshot.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reachable", e.getValue());
            item.put("lastCheck", probe.getLastCheckTime(e.getKey()));
            details.put(e.getKey(), item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("servers", details);
        result.put("count", snapshot.size());
        return ApiResponse.ok(result);
    }

    /**
     * 手动触发一次活性探测
     */
    @PostMapping("/probe")
    public ApiResponse<String> triggerProbe() {
        probe.probeNow();
        return ApiResponse.ok("probe triggered");
    }

    /**
     * 综合状态：探测 + 对账情况
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reachability", probe.getAllReachability());
        result.put("reconcileCount", reconciler.getReconcileCount());
        result.put("lastReconcileTime", reconciler.getLastReconcileTime());
        result.put("timestamp", System.currentTimeMillis());
        return ApiResponse.ok(result);
    }

    /**
     * 手动触发对账
     */
    @PostMapping("/reconcile")
    public ApiResponse<String> triggerReconcile() {
        reconciler.reconcileNow();
        return ApiResponse.ok("reconcile triggered");
    }
}