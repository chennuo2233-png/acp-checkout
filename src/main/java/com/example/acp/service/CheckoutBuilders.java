package com.example.acp.service;

import java.util.*;

/**
 * 构造与更新 Checkout Session 的静态工具。
 * STRIPE_ACCOUNT_ID / SHIP_STANDARD_CENTS / TAX_RATE_BPS / *URL 来自环境变量。
 */
public class CheckoutBuilders {

    /** 你的 Stripe 帐号 ID（如 acct_123…）；在部署平台的环境变量里配置即可 */
    private static final String STRIPE_ACCOUNT_ID =
            System.getenv().getOrDefault("STRIPE_ACCOUNT_ID", "acct_TEST123");

    /** 运费（分）与税率（基点，万分制），来自环境变量 */
    private static final int SHIP_STANDARD_CENTS =
            Integer.parseInt(System.getenv().getOrDefault("SHIP_STANDARD_CENTS", "100"));   // 默认 $1.00
    private static final int TAX_RATE_BPS =
            Integer.parseInt(System.getenv().getOrDefault("TAX_RATE_BPS", "1000"));         // 默认 10%

    /** 可选：政策链接来自环境变量（有则加入 links） */
    private static final String TOS_URL =
            System.getenv().getOrDefault("TOS_URL", "https://chennuo2233.wixsite.com/albertselfsaling/terms");
    private static final String PRIVACY_URL =
            System.getenv().getOrDefault("PRIVACY_URL", "");
    private static final String RETURNS_URL =
            System.getenv().getOrDefault("RETURNS_URL", "");

    /** 创建新的会话（富状态） */
    public static Map<String, Object> buildInitialSession(String sessionId, Map<String, Object> req) {
        Map<String, Object> session = new HashMap<>();
        session.put("id", sessionId);

        // 货币：优先取本次请求的 currency，其次默认 usd
        String currency = String.valueOf(req.getOrDefault("currency", "usd")).toLowerCase();
        session.put("currency", currency);

        // 如果没有地址，则 not_ready_for_payment；有地址则 ready_for_payment
        boolean hasAddress = req.containsKey("fulfillment_address");
        session.put("status", hasAddress ? "ready_for_payment" : "not_ready_for_payment");
        if (hasAddress) session.put("fulfillment_address", req.get("fulfillment_address"));

        // ── items → line_items ─────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) req.getOrDefault("items", List.of());

        List<Map<String, Object>> lineItems = new ArrayList<>();
        int itemsBaseAmount = 0;

        for (Map<String, Object> it : items) {
            String itemId = String.valueOf(it.get("id"));
            int qty = ((Number) it.getOrDefault("quantity", 1)).intValue();

            // 用真单价（单位分）；没有就兜底 100
            int unitPrice = ((Number) it.getOrDefault("unit_price_cents", 100)).intValue();
            int base = unitPrice * qty;
            int discount = 0;

            int tax = 0;
            if (hasAddress) {
                tax = (int) Math.round(base * (TAX_RATE_BPS / 10000.0));
            }
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

        // ── Fulfillment options（示例：不对运费计税） ───────────────────────
        List<Map<String, Object>> fulfillmentOptions = new ArrayList<>();
        if (hasAddress) {
            Map<String, Object> standard = new HashMap<>();
            standard.put("type", "shipping");
            standard.put("id", "fulfillment_option_standard");
            standard.put("title", "Standard");
            standard.put("subtitle", "Arrives in 4-5 days");
            standard.put("carrier", "USPS");
            // 下面两个时间仅演示用途
            standard.put("earliest_delivery_time", "2025-10-14T00:00:00Z");
            standard.put("latest_delivery_time", "2025-10-16T00:00:00Z");
            standard.put("subtotal", SHIP_STANDARD_CENTS);
            standard.put("tax", 0);
            standard.put("total", SHIP_STANDARD_CENTS);
            fulfillmentOptions.add(standard);

            session.put("fulfillment_options", fulfillmentOptions);
            session.put("fulfillment_option_id", "fulfillment_option_standard");
        } else {
            session.put("fulfillment_options", fulfillmentOptions);
        }

        // ── totals 计算 ────────────────────────────────────────────────────
        int taxTotal = hasAddress ? (int) Math.round(itemsBaseAmount * (TAX_RATE_BPS / 10000.0)) : 0;
        int fulfillmentTotal = hasAddress ? SHIP_STANDARD_CENTS : 0;
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

        // links：至少 terms_of_use；如有隐私/退货，也一并附上
        List<Map<String, Object>> links = new ArrayList<>();
        if (TOS_URL != null && !TOS_URL.isBlank()) {
            links.add(Map.of("type", "terms_of_use", "url", TOS_URL));
        }
        if (PRIVACY_URL != null && !PRIVACY_URL.isBlank()) {
            links.add(Map.of("type", "privacy_policy", "url", PRIVACY_URL));
        }
        if (RETURNS_URL != null && !RETURNS_URL.isBlank()) {
            links.add(Map.of("type", "return_policy", "url", RETURNS_URL));
        }
        session.put("links", links);

        return session;
    }

    /** 合并更新请求并重算 totals/line_items */
    @SuppressWarnings("unchecked")
    public static void applyUpdates(Map<String, Object> session, Map<String, Object> req) {
        Map<String, Object> merged = new HashMap<>(session);

        // 合并本次更新的地址/配送选项/货币
        if (req.containsKey("fulfillment_address"))
            merged.put("fulfillment_address", req.get("fulfillment_address"));
        if (req.containsKey("fulfillment_option_id"))
            merged.put("fulfillment_option_id", req.get("fulfillment_option_id"));
        if (req.containsKey("currency"))
            merged.put("currency", req.get("currency"));
        else if (session.containsKey("currency"))
            merged.put("currency", session.get("currency"));

        // 若这次请求显式携带了 items（允许空数组表示清空购物车），就以它为准；
        // 否则从现有 line_items 反推最小 items（id + quantity + unit_price_cents），以“保留购物车”
        if (req.containsKey("items") && req.get("items") instanceof List) {
            merged.put("items", req.get("items"));
        } else {
            List<Map<String, Object>> prevLines =
                    (List<Map<String, Object>>) session.getOrDefault("line_items", List.of());
            List<Map<String, Object>> derivedItems = new ArrayList<>();
            for (Map<String, Object> li : prevLines) {
                Object itemObj = li.get("item");
                if (itemObj instanceof Map) {
                    Map<String, Object> it = (Map<String, Object>) itemObj;
                    int qty = ((Number) it.getOrDefault("quantity", 1)).intValue();
                    int base = ((Number) li.getOrDefault("base_amount", 0)).intValue();
                    int unitPrice = qty > 0 ? Math.max(0, base / qty) : base;

                    Map<String, Object> minimal = new HashMap<>();
                    minimal.put("id", it.get("id"));
                    minimal.put("quantity", qty);
                    minimal.put("unit_price_cents", unitPrice); // ✅ 把单价带回
                    derivedItems.add(minimal);
                }
            }
            merged.put("items", derivedItems);
        }

        // 用合并后的请求重建完整会话（行项/税费/合计都会被重算）
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
        // 可替换为你的正式订单详情页
        order.put("permalink_url", "https://www.testshop.com/orders/" + order.get("id"));
        session.put("order", order);
    }
}
