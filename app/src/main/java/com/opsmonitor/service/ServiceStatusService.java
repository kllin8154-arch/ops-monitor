package com.opsmonitor.service;

import com.opsmonitor.model.AggregatedStatus;

/**
 * 服务状态聚合接口
 * 统一查询 Prometheus / Docker，返回聚合状态
 */
public interface ServiceStatusService {

    /**
     * 获取系统聚合状态
     */
    AggregatedStatus getAggregatedStatus();
}