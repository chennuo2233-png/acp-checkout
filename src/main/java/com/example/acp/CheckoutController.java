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
    @Autowired private com.example.acp.service.ProductService productService;

    /* ---------- 1. Create session ---------- */
    @PostMapping("/checkout_sessions")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        // 先补齐 items 的真实单价与币种（从 feed 查）
        enrichItemsWithPrice(req);
        System.out.println("DEBUG items after enrich: " + req.get("items"));


        String sessionId = "cs_" + UUID.randomUUID();
        Map<String, Object> session = CheckoutBuilders.buildInitialSession(sessionId, req);
        store.put(sessionId, session);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /* ---------- 2. Update session（幂等） ---------- */
    @PostMapping("/checkout_sessions/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {

        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        String key = (idemKey == null || idemKey.isBlank()) ? null : ("update:" + id + ":" + idemKey);
        if (key != null) {
            Map<String, Object> cached = idempotencyStore.getIfReady(key);
            if (cached != null) return ResponseEntity.ok(cached);
            boolean begun = idempotencyStore.tryBegin(key);
            if (!begun) {
                for (int i = 0; i < 10; i++) {
                    Map<String, Object> c = idempotencyStore.getIfReady(key);
                    if (c != null) return ResponseEntity.ok(c);
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "idempotency_in_progress",
                                     "message", "Please retry later with the same Idempotency-Key"));
            }
        }

        // ★ 先补齐单价/币种，再重建会话（保证“只传地址”也能保留购物车与真价格）
        enrichItemsWithPrice(req);
        CheckoutBuilders.applyUpdates(session, req);
        store.put(id, session);

        // 权威更新：通知 OpenAI
        orderEventPublisher.publishOrderUpdated(session);

        if (key != null) idempotencyStore.commit(key, session);
        return ResponseEntity.ok(session);
    }

    /* ---------- 3. Complete（幂等 + 支付分流） ---------- */
    @PostMapping("/checkout_sessions/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {

        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        // 幂等：同键直接返回；并发短轮询；失败则409
        String key = (idemKey == null || idemKey.isBlank()) ? null : ("complete:" + id + ":" + idemKey);
        if (key != null) {
            Map<String, Object> cached = idempotencyStore.getIfReady(key);
            if (cached != null) return ResponseEntity.ok(cached);
            boolean begun = idempotencyStore.tryBegin(key);
            if (!begun) {
                for (int i = 0; i < 10; i++) {
                    Map<String, Object> c = idempotencyStore.getIfReady(key);
                    if (c != null) return ResponseEntity.ok(c);
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "idempotency_in_progress",
                                     "message", "Please retry later with the same Idempotency-Key"));
            }
        }

        // 计算应付金额（从 totals 里取 total；找不到则按 0）
        long payable = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totals = (List<Map<String, Object>>) session.getOrDefault("totals", List.of());
        for (Map<String, Object> t : totals) {
            if ("total".equals(String.valueOf(t.get("type")))) {
                payable = ((Number) t.getOrDefault("amount", 0)).longValue();
                break;
            }
        }
        String currency = String.valueOf(session.getOrDefault("currency", "usd")).toLowerCase();


        
        // 支付令牌（兼容 payment / payment_data / 顶层三种写法）
        String token = "";
        if (req.containsKey("payment") && req.get("payment") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) req.get("payment");
            token = String.valueOf(p.getOrDefault("payment_method_token", ""));
        } else if (req.containsKey("payment_data") && req.get("payment_data") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pd = (Map<String, Object>) req.get("payment_data");
            token = String.valueOf(pd.getOrDefault("payment_method_token", ""));
        } else {
            token = String.valueOf(req.getOrDefault("payment_method_token", ""));
        }

        Map<String, Object> responseBody;
        try {
            // 从请求头拿幂等键（方法签名已有 idemKey）
            String connectAccountId = System.getenv("STRIPE_CONNECT_ACCOUNT"); // 或者从你自定义的头里读取
            Map<String, String> metadata = Map.of("checkout_session_id", id, "source", "openai-agentic-checkout");
            
            Map<String, Object> payResult = paymentService.charge(
    token,
    payable,
    currency,
    idemKey,            // 透传给 Stripe 的 idempotency key
    connectAccountId,   // 多商户场景可切换到被连商户
    metadata);

            session.putAll(payResult); // 写入 status / payment_intent_id

            if ("succeeded".equals(payResult.get("status"))) {
                // ---------- buyer 兜底：从 fulfillment_address.name 拆 first_name，且不覆盖已存在值 ----------
@SuppressWarnings("unchecked")
Map<String, Object> buyer = null;

// 1) 先看 session 是否已有 buyer（例如此前已保存）
if (session.get("buyer") instanceof Map) {
    buyer = (Map<String, Object>) session.get("buyer");
}

// 2) 没有的话，看看这次 complete 的请求体里是否带了 buyer
if (buyer == null && req.get("buyer") instanceof Map) {
    buyer = new HashMap<>((Map<String, Object>) req.get("buyer")); // 拷贝一份，避免直接引用 req
}

// 3) 如果仍然没有，就新建一个容器（只在确有字段可填时才落盘）
if (buyer == null) {
    buyer = new HashMap<>();
}

// 4) 若缺少 first_name，就尝试从 fulfillment_address.name 拆
Object firstNameObj = buyer.get("first_name");
if (firstNameObj == null || String.valueOf(firstNameObj).isBlank()) {
    Map<String, Object> fa = null;
    if (session.get("fulfillment_address") instanceof Map) {
        fa = (Map<String, Object>) session.get("fulfillment_address");
    } else if (req.get("fulfillment_address") instanceof Map) {
        fa = (Map<String, Object>) req.get("fulfillment_address");
    }
    if (fa != null) {
        Object nameObj = fa.get("name");
        if (nameObj instanceof String name && !name.isBlank()) {
            String first = name.trim().split("\\s+")[0]; // 仅取第一个词作为 first_name
            if (!first.isBlank()) {
                buyer.put("first_name", first);
            }
        }
    }
}

// 5) 若缺少 email，尽量从请求里拾取（不伪造）
if (!buyer.containsKey("email")) {
    // 优先从请求体 buyer.email 拿（如果本次带了）
    if (req.get("buyer") instanceof Map<?,?> rb) {
        Object e = ((Map<String, Object>) rb).get("email");
        if (e instanceof String es && !es.isBlank()) {
            buyer.put("email", es);
        }
    }
    // 其次尝试顶层的 email（有些集成会放在根上）
    if (!buyer.containsKey("email") && req.get("email") instanceof String es2 && !((String) es2).isBlank()) {
        buyer.put("email", (String) es2);
    }
}

// 6) 如果 buyer 里至少有一个关键字段（first_name 或 email），就写回 session
if (!buyer.isEmpty()) {
    session.put("buyer", buyer);
}

                CheckoutBuilders.markCompleted(session, req);     // 生成 order + 状态
                session.put("status", "completed");
                store.put(id, session);

                // 成功：发送 order.created
                orderEventPublisher.publishOrderCreated(session);
                responseBody = session;
                session.put("updated_time", java.time.Instant.now().toString());
            } else {
                // 不改变会话状态（通常仍为 ready_for_payment）
                String failure = String.valueOf(
                    payResult.getOrDefault("failure_message", "Payment failed")
                    );
                // 将错误写入 messages，便于 ChatGPT 向用户展示
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> msgs = (List<Map<String, Object>>) session.get("messages");
                if (msgs == null) {
                    msgs = new ArrayList<>();
                    session.put("messages", msgs);
                }
                Map<String, Object> m = new HashMap<>();
                m.put("type", "payment_error");
                m.put("text", failure);
                msgs.add(m);
                store.put(id, session);
                responseBody = session;
            }

        } catch (Exception e) {
            e.printStackTrace();
            responseBody = Map.of("error", e.getMessage());
            if (key != null) idempotencyStore.commit(key, responseBody);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
        }

        if (key != null) idempotencyStore.commit(key, responseBody);
        return ResponseEntity.ok(responseBody);
    }

    /* ---------- 4. Cancel（发送 order.updated） ---------- */
    @PostMapping("/checkout_sessions/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("id") String id) {
        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        session.put("status", "canceled");
        store.put(id, session);
        orderEventPublisher.publishOrderUpdated(session);
        return ResponseEntity.ok(session);
    }

    /* ---------- 5. Get session ---------- */
    @GetMapping("/checkout_sessions/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        Map<String, Object> session = store.get(id);
        if (session == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(session);
    }

    /* ---------- 工具：把每个 item 补齐 unit_price_cents 与 currency ---------- */
    @SuppressWarnings("unchecked")
    private void enrichItemsWithPrice(Map<String, Object> req) {
        Object itemsObj = req.get("items");
        if (!(itemsObj instanceof List)) return;

        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        for (Map<String, Object> it : items) {
            if (it == null) continue;
            String itemId = String.valueOf(it.get("id"));
            if (itemId == null || itemId.isBlank()) continue;

            // 仅当没有传单价时，才从 feed 查价格并回填
            if (!it.containsKey("unit_price_cents")) {
                productService.findPriceById(itemId).ifPresent(p -> {
                    it.put("unit_price_cents", p.unitCents);
                    // 若请求还没带 currency，就用商品的币种；以第一个商品为准
                    req.putIfAbsent("currency", p.currency);
                });
            }
        }
    }

}
