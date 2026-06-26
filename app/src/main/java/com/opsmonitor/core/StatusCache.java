package com.opsmonitor.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻量级状态缓存
 *
 * 解决：/api/system/status 每 5 秒刷新 → 每次都查 Prometheus HTTP API
 *
 * 机制：
 * - TTL = 5 秒
 * - 超过 TTL 才允许重新查询
 * - 线程安全（AtomicReference + volatile timestamp）
 * - 零外部依赖，纯 JDK 实现
 *
 * 内存安全：
 * - 只缓存 1 个对象引用，内存占用恒定
 * - 旧对象被 GC 回收
 */
@Slf4j
@Component
public class StatusCache<T> {

    /** 缓存有效期（毫秒） */
    private static final long TTL_MS = 5000;

    private final AtomicReference<CacheEntry<T>> cache = new AtomicReference<>();

    /**
     * 获取缓存值
     * @return 缓存值，过期或无缓存返回 null
     */
    public T get() {
        CacheEntry<T> entry = cache.get();
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) return null;
        return entry.value;
    }

    /**
     * 写入缓存
     */
    public void put(T value) {
        cache.set(new CacheEntry<>(value, System.currentTimeMillis()));
    }

    /**
     * 缓存是否有效
     */
    public boolean isValid() {
        return get() != null;
    }

    /**
     * 清除缓存
     */
    public void invalidate() {
        cache.set(null);
    }

    private static class CacheEntry<T> {
        final T value;
        final long timestamp;

        CacheEntry(T value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
}