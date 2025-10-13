package com.example.acp;

import com.example.acp.store.SessionStore;
import com.example.acp.service.CheckoutBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.example.acp.service.PaymentService;          
import com.stripe.model.PaymentIntent; 

@RestController
@RequestMapping("/api")
public class CheckoutController {

    @Autowired private SessionStore store;
    @Autowired private PaymentService paymentService;

    // Create: 返回 201，包含完整会话状态
    @PostMapping("/checkout_sessions")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        System.out.println("✅ 收到 /checkout_sessions 请求");
        String sessionId = "cs_" + UUID.randomUUID();
        Map<String, Object> session = CheckoutBuilders.buildInitialSession(sessionId, req);
        store.put(sessionId, session);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    // Update: 返回 200，更新 items/地址/配送选项
    @PostMapping("/checkout_sessions/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> req) {

        System.out.println("收到更新请求: " + req);

        Map<String, Object> session = store.get(id);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        CheckoutBuilders.applyUpdates(session, req);
        store.put(id, session);
        return ResponseEntity.ok(session);
    }

    @PostMapping("/checkout_sessions/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> req) {

        Map<String, Object> session = store.get(id);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        /* ① 取 delegated_payment_token */
        String token = (String) req.getOrDefault("payment_method_token", "");

        /* ② 计算应付金额（从 totals 里拿 type == "total" 的 amount） */
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totals =
                (List<Map<String, Object>>) session.getOrDefault("totals", List.of());
        long payable = totals.stream()
                .filter(t -> "total".equals(t.get("type")))
                .mapToLong(t -> ((Number) t.get("amount")).longValue())
                .findFirst()
                .orElse(0);

        try {
            /* ③ 创建 Stripe PaymentIntent（Delegated Payment） */
            PaymentIntent pi = paymentService.createIntent(payable, "usd", token);

            /* ④ 将订单写回会话并标记完成 */
            session.put("payment_intent_id", pi.getId());
            CheckoutBuilders.markCompleted(session, req);
            store.put(id, session);

            /* ⑤ TODO：触发 order.created / payment_succeeded webhook */

            return ResponseEntity.ok(session);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Cancel: 返回 200 或 405（已完成或已取消不可取消）
    @PostMapping("/checkout_sessions/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("id") String id) {
        Map<String, Object> session = store.get(id);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String status = String.valueOf(session.getOrDefault("status", "not_ready_for_payment"));
        if ("completed".equals(status) || "canceled".equals(status)) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        session.put("status", "canceled");
        store.put(id, session);
        return ResponseEntity.ok(session);
    }

    // Get: 返回 200 或 404（查当前会话状态）
    @GetMapping("/checkout_sessions/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        Map<String, Object> session = store.get(id);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(session);
    }
}
