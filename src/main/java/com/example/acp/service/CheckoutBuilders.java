package com.example.acp.service;

import java.util.*;

/**
 * 构造与更新 Checkout Session 的静态工具。
 * STRIPE_ACCOUNT_ID 来自环境变量，未设置时用占位符方便本地调试。
 */
public class CheckoutBuilders {

    /** 你的 Stripe 帐号 ID（如 acct_123…）；在部署平台的环境变量里配置即可 */
    private static final String STRIPE_ACCOUNT_ID =
            System.getenv().getOrDefault("STRIPE_ACCOUNT_ID", "acct_TEST123");

    /** 创建新的会话 */
    public static Map<String, Object> buildInitialSession(String sessionId, Map<String, Object> req) {

        Map<String, Object> session = new HashMap<>();
        session.put("id", sessionId);
        session.put("currency", "usd");

        // 如果没有地址，则 not_ready_for_payment；有地址则 ready_for_payment
        boolean hasAddress = req.containsKey("fulfillment_address");
        session.put("status", hasAddress ? "ready_for_payment" : "not_ready_for_payment");
        if (hasAddress) session.put("fulfillment_address", req.get("fulfillment_address"));

        // ── items → line_items ─────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) req.getOrDefault("items", List.of());

        List<Map<String, Object>> lineItems = new ArrayList<>();
        int itemsBaseAmount = 0;

        for (Map<String, Object> it : items) {
            String itemId = String.valueOf(it.get("id"));
            int qty = ((Number) it.getOrDefault("quantity", 1)).intValue();

            // TODO：后续用 Wix 实价替换
            int unitPrice = 100;
            int base = unitPrice * qty;
            int discount = 0;
            int tax = hasAddress ? (int) Math.round(base * 0.10) : 0;
            int total = base - discount + tax;

            Map<String, Object> line = new HashMap<>();
            line.put("id", "li_" + UUID.randomUUID());
            line.put("item", Map.of("id", itemId, "quantity", qty));
            line.put("base_amount", base);
            line.put("discount", discount);
            line.put("subtotal", base - discount);
            line.put("tax", tax);
            line.put("total", total);
            lineItems.add(line);

            itemsBaseAmount += base;
        }
        session.put("line_items", lineItems);

        // ── Fulfillment options ────────────────────────────────────────────
        List<Map<String, Object>> fulfillmentOptions = new ArrayList<>();
        if (hasAddress) {
            Map<String, Object> standard = new HashMap<>();
            standard.put("type", "shipping");
            standard.put("id", "fulfillment_option_standard");
            standard.put("title", "Standard");
            standard.put("subtitle", "Arrives in 4-5 days");
            standard.put("carrier", "USPS");
            standard.put("earliest_delivery_time", "2025-10-14T00:00:00Z");
            standard.put("latest_delivery_time", "2025-10-16T00:00:00Z");
            standard.put("subtotal", 100);
            standard.put("tax", 0);
            standard.put("total", 100);
            fulfillmentOptions.add(standard);

            session.put("fulfillment_options", fulfillmentOptions);
            session.put("fulfillment_option_id", "fulfillment_option_standard");
        } else {
            session.put("fulfillment_options", fulfillmentOptions);
        }

        // ── totals 计算 ────────────────────────────────────────────────────
        int taxTotal = hasAddress ? (int) Math.round(itemsBaseAmount * 0.10) : 0;
        int fulfillmentTotal = hasAddress ? 100 : 0;
        int subtotal = itemsBaseAmount;
        int total = subtotal + taxTotal + fulfillmentTotal;

        List<Map<String, Object>> totals = new ArrayList<>();
        totals.add(Map.of("type", "items_base_amount", "display_text", "Item(s) total", "amount", itemsBaseAmount));
        totals.add(Map.of("type", "subtotal", "display_text", "Subtotal", "amount", subtotal));
        totals.add(Map.of("type", "tax", "display_text", "Tax", "amount", taxTotal));
        if (hasAddress) {
            totals.add(Map.of("type", "fulfillment", "display_text", "Fulfillment", "amount", fulfillmentTotal));
        }
        totals.add(Map.of("type", "total", "display_text", "Total", "amount", total));
        session.put("totals", totals);

        // ── 规范要求：payment_provider / messages / links ────────────────
        session.put("payment_provider", Map.of(
                "provider", "stripe",
                "stripe_account_id", STRIPE_ACCOUNT_ID,          
                "supported_payment_methods", List.of("card")
        ));

        session.put("messages", new ArrayList<>());
        session.put("links", List.of(
                Map.of("type", "terms_of_use", "url", "https://www.testshop.com/legal/terms-of-use")
        ));

        return session;
    }

    /** 合并更新请求并重算 totals/line_items */
    public static void applyUpdates(Map<String, Object> session, Map<String, Object> req) {

        Map<String, Object> merged = new HashMap<>(session);

        if (req.containsKey("fulfillment_address"))
            merged.put("fulfillment_address", req.get("fulfillment_address"));
        if (req.containsKey("fulfillment_option_id"))
            merged.put("fulfillment_option_id", req.get("fulfillment_option_id"));
        if (req.containsKey("items") && req.get("items") instanceof List)
            merged.put("items", req.get("items"));

        Map<String, Object> rebuilt = buildInitialSession((String) session.get("id"), merged);
        session.clear();
        session.putAll(rebuilt);
    }

    /** 把会话状态置为 completed，并生成订单对象 */
    public static void markCompleted(Map<String, Object> session, Map<String, Object> req) {

        session.put("status", "completed");
        Map<String, Object> order = new HashMap<>();
        order.put("id", "ord_" + UUID.randomUUID());
        order.put("checkout_session_id", session.get("id"));
        order.put("permalink_url", "https://www.testshop.com/orders/" + order.get("id"));
        session.put("order", order);
    }
}
