package com.example.acp;

import com.example.acp.service.CheckoutBuilders;
import com.example.acp.service.PaymentService;
import com.example.acp.store.SessionStore;

import com.example.acp.service.OrderEventPublisher;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Checkout API —— 对应 OpenAI Commerce Spec
 */
@RestController
@RequestMapping("/api")
public class CheckoutController {

    @Autowired private SessionStore store;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderEventPublisher orderEventPublisher;
    @Autowired private com.example.acp.store.IdempotencyStore idempotencyStore;


    /* ---------- 1. Create checkout session ---------- */
    @PostMapping("/checkout_sessions")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        String sessionId = "cs_" + UUID.randomUUID();
        Map<String, Object> session = CheckoutBuilders.buildInitialSession(sessionId, req);
        store.put(sessionId, session);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

// Update: 返回 200，更新 items/地址/配送选项
    @PostMapping("/checkout_sessions/{id}")
    public ResponseEntity<Map<String, Object>> update(
        @PathVariable("id") String id,
        @RequestBody Map<String, Object> req,
        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {

    Map<String, Object> session = store.get(id);
    if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

    // ===== 幂等：若已有完成的同键响应，直接返回；否则占位防并发重复 =====
    String key = (idemKey == null || idemKey.isBlank())
            ? null
            : "update:" + id + ":" + idemKey;

    if (key != null) {
        Map<String, Object> cached = idempotencyStore.getIfReady(key);
        if (cached != null) return ResponseEntity.ok(cached);

        boolean begun = idempotencyStore.tryBegin(key);
        if (!begun) {
            Map<String, Object> cached2 = idempotencyStore.getIfReady(key);
            if (cached2 != null) return ResponseEntity.ok(cached2);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "idempotency_in_progress",
                                 "message", "Please retry later with the same Idempotency-Key"));
        }
    }

    // ===== 业务：按本次请求 + 现有会话重建完整富状态 =====
    CheckoutBuilders.applyUpdates(session, req);            // 会保留旧 items 的修复版
    store.put(id, session);
    orderEventPublisher.publishOrderUpdated(session);       // 更新事件 webhook（你已有） :contentReference[oaicite:2]{index=2}

    if (key != null) idempotencyStore.commit(key, session);
    return ResponseEntity.ok(session);
}


    /* ---------- 3. Complete & pay ---------- */
    @PostMapping("/checkout_sessions/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable("id") String id,       
            @RequestBody Map<String, Object> req) {
        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        /* ① 读取 delegated / stub token */
        String token = String.valueOf(req.getOrDefault("payment_method_token", ""));

        /* ② 计算总金额（从 totals 里找 type == total 的 amount） */
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totals = (List<Map<String, Object>>) session.getOrDefault("totals", List.of());
        long payable = totals.stream()
                .filter(t -> "total".equals(t.get("type")))
                .mapToLong(t -> ((Number) t.get("amount")).longValue())
                .findFirst()
                .orElse(0);

        try {
            /* ③ 调用 PaymentService：真 Stripe 或 Stub */
            Map<String, Object> payResult = paymentService.charge(token, payable, "usd");
            session.putAll(payResult);                              // 写入 status / payment_intent_id

            /* ④ 根据支付结果更新会话状态 */
            if ("succeeded".equals(payResult.get("status"))) {
                CheckoutBuilders.markCompleted(session, req);       // 置为 completed
            } else {
                session.put("status", "payment_failed");
            }
            store.put(id, session);

            if ("succeeded".equals(payResult.get("status"))) {
                CheckoutBuilders.markCompleted(session, req);       // 置为 completed
                orderEventPublisher.publishOrderCreated(session);   // ✅ 触发权威事件
                } else {
                    session.put("status", "payment_failed");
                }

            return ResponseEntity.ok(session);

        } catch (Exception e) {                                     // StripeException 等
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------- 4. Cancel session ---------- */
    @PostMapping("/checkout_sessions/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("id") String id)  {
        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        String status = String.valueOf(session.getOrDefault("status", "not_ready_for_payment"));
        if ("completed".equals(status) || "canceled".equals(status)) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        session.put("status", "canceled");
        store.put(id, session);
        return ResponseEntity.ok(session);
    }

    /* ---------- 5. Get session ---------- */
    @GetMapping("/checkout_sessions/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(session);
    }
}
