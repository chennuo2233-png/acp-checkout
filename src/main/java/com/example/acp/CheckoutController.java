package com.example.acp;

import com.example.acp.service.CheckoutBuilders;
import com.example.acp.service.PaymentService;
import com.example.acp.store.SessionStore;
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
    @Autowired private PaymentService paymentService;   // 新增 Stub / Stripe 二合一支付服务

    /* ---------- 1. Create checkout session ---------- */
    @PostMapping("/checkout_sessions")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        String sessionId = "cs_" + UUID.randomUUID();
        Map<String, Object> session = CheckoutBuilders.buildInitialSession(sessionId, req);
        store.put(sessionId, session);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /* ---------- 2. Update checkout session ---------- */
    @PatchMapping("/checkout_sessions/{id}")            // ★ 改成 PATCH 更符合规范
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> req) {

        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        CheckoutBuilders.applyUpdates(session, req);
        store.put(id, session);
        return ResponseEntity.ok(session);
    }

    /* ---------- 3. Complete & pay ---------- */
    @PostMapping("/checkout_sessions/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable String id,
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
            Map<String, Object> payResult = paymentService.charge(payable, "usd", token);
            session.putAll(payResult);                              // 写入 status / payment_intent_id

            /* ④ 根据支付结果更新会话状态 */
            if ("succeeded".equals(payResult.get("status"))) {
                CheckoutBuilders.markCompleted(session, req);       // 置为 completed
            } else {
                session.put("status", "payment_failed");
            }
            store.put(id, session);

            /* ⑤ TODO: 触发 order.created / payment_succeeded webhook */

            return ResponseEntity.ok(session);

        } catch (Exception e) {                                     // StripeException 等
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------- 4. Cancel session ---------- */
    @PostMapping("/checkout_sessions/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
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
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(session);
    }
}
