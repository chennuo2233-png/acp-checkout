package com.example.acp.store;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简会话存储（内存版）
 * - 单实例演示用途；生产可换成 Redis/数据库
 */
@Component
public class SessionStore {

    // checkout_session_id -> session(full state map)
    private final ConcurrentHashMap<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public Map<String, Object> get(String id) {
        return store.get(id);
    }

    public void put(String id, Map<String, Object> session) {
        store.put(id, session);
    }

    public void remove(String id) {
        store.remove(id);
    }

    /** 新增：根据 payment_intent_id 找回会话（找不到返回 null） */
    public Map<String, Object> findByPaymentIntentId(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) return null;
        for (Map.Entry<String, Map<String, Object>> e : store.entrySet()) {
            Map<String, Object> session = e.getValue();
            Object pi = session.get("payment_intent_id");
            if (pi != null && paymentIntentId.equals(pi.toString())) {
                return session;
            }
        }
        return null;
    }
}
