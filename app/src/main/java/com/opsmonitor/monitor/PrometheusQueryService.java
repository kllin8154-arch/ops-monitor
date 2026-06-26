package com.opsmonitor.monitor;

import java.util.Map;
import java.util.Optional;

/**
 * Prometheus 查询服务接口
 * 封装 Prometheus HTTP API 的 PromQL 查询能力
 */
public interface PrometheusQueryService {

    /**
     * 执行即时查询 (instant query)
     * GET /api/v1/query?query=...
     *
     * @param promql PromQL 表达式
     * @return 查询结果值（单值），查询失败返回 empty
     */
    Optional<Double> queryScalar(String promql);

    /**
     * 查询 up 指标，返回所有 job 的状态
     * @return job名 -> 1(UP) / 0(DOWN)
     */
    Map<String, Integer> queryAllJobStatus();

    /**
     * 查询所有 managed_by="ops-monitor" 实例的 up 状态 (9G-1)
     * 单次查询替代逐个 HTTP 探测
     * @return exporterId/instance -> 1(UP) / 0(DOWN)
     */
    Map<String, Integer> queryExporterUpStatus();
}