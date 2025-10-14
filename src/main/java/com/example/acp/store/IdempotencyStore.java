package com.example.acp.store;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简幂等缓存（内存版）：
 * - tryBegin(key): 第一次请求占位，返回 true；并发重复返回 false。
 * - commit(key, body): 写入最终响应，并设置过期时间。
 * - getIfReady(key): 若已有已完成的缓存且未过期，返回缓存响应；否则返回 null。
 *
 * 说明：
 * - 仅用于单实例/演示；生产推荐用共享存储（如 Redis）替代。
 * - TTL 默认 5 分钟，可按需调整。
 */
@Component
public class IdempotencyStore {

    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;

    private static final class Entry {
        volatile boolean inProgress;            // 是否占位中
        volatile long expiresAt;                // 过期时间戳（ms）
        volatile Map<String, Object> body;      // 已完成时的响应体
    }

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMs;

    public IdempotencyStore() {
        this(DEFAULT_TTL_MS);
    }

    public IdempotencyStore(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 第一次请求占位；并发重复将返回 false（由调用方决定返回 409/425 或短暂重试） */
    public boolean tryBegin(String key) {
        long now = System.currentTimeMillis();
        Entry fresh = new Entry();
        fresh.inProgress = true;
        fresh.expiresAt = now + ttlMs;

        Entry prev = cache.putIfAbsent(key, fresh);
        if (prev == null) return true;                  // 第一次占位成功

        // 发现旧条目：若已过期，替换为新占位
        if (prev.expiresAt < now) {
            cache.replace(key, prev, fresh);
            return true;
        }
        // 旧条目未过期：若已有完成体，调用方可以直接 getIfReady 返回它
        return false;
    }

    /** 写入最终响应体并标记完成（供后续相同 key 直接命中） */
    public void commit(String key, Map<String, Object> body) {
        long now = System.currentTimeMillis();
        Entry e = cache.computeIfAbsent(key, k -> new Entry());
        e.inProgress = false;
        e.body = body;
        e.expiresAt = now + ttlMs;
    }

    /** 若已有“已完成且未过期”的缓存，返回它；否则返回 null */
    public Map<String, Object> getIfReady(String key) {
        long now = System.currentTimeMillis();
        Entry e = cache.get(key);
        if (e == null) return null;
        if (e.expiresAt < now) {
            cache.remove(key, e);
            return null;
        }
        return (e.inProgress || e.body == null) ? null : e.body;
    }

    /** 可选：手动清理（暂不必须） */
    public void clear(String key) {
        cache.remove(key);
    }
}
