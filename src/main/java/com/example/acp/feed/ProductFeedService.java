package com.example.acp.feed;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ProductFeedService {
    // 缓存最新 feed 数据
    private final AtomicReference<List<Map<String, Object>>> cached =
            new AtomicReference<>(new ArrayList<>());
    private volatile String lastGeneratedAt = OffsetDateTime.now().toString();

    // 注入真实的 WixClient（带 @Component 注解）
    private final WixClient wixClient;

    public ProductFeedService(WixClient wixClient) {
        this.wixClient = wixClient;
    }

    /**
     * 每 15 分钟刷新一次产品 feed，符合 OpenAI 要求的刷新频率。
     */
    @Scheduled(initialDelay = 0, fixedRate = 15 * 60 * 1000)
    public void refreshFeed() {
        try {
            List<WixProduct> wixProducts = wixClient.fetchProducts();
            List<Map<String, Object>> mapped = new ArrayList<>();

            for (WixProduct p : wixProducts) {
                Map<String, Object> item = new HashMap<>();

                // 1. 基本数据（Required）
                String id = (p.getSku() != null && !p.getSku().isEmpty())
                            ? p.getSku() : p.getId();
                item.put("id", safeId(id));                                          // id
                item.put("title", safeTitle(p.getName()));                            // title
                item.put("description", stripHtml(p.getDescription()));               // description
                String link = p.getProductPageUrl().getBase()
                              + p.getProductPageUrl().getPath();
                item.put("link", ensureHttps(link));                                  // link

                // 2. 媒体（Required）
                item.put("image_link",
                         ensureHttps(p.getMedia().getMainMedia().getImage().getUrl())); // image_link

                // 3. Item Information（Required）
                item.put("product_category", p.getProductType());                    // product_category
                item.put("brand", "Your Store");                                     // brand
                item.put("material", "N/A");                                         // material
                item.put("weight", p.getWeight() + " kg");                           // weight

                // 4. 价格与促销
                Map<String, Object> price = new HashMap<>();
                price.put("amount", p.getPriceData().getPrice());                    // price.amount
                price.put("currency", p.getPriceData().getCurrency());               // price.currency
                item.put("price", price);                                            // price

                if (p.getPriceData().getDiscountedPrice() 
                    < p.getPriceData().getPrice()) {
                    Map<String, Object> sale = new HashMap<>();
                    sale.put("amount", p.getPriceData().getDiscountedPrice());
                    sale.put("currency", p.getPriceData().getCurrency());
                    item.put("sale_price", sale);                                    // sale_price
                    item.put("sale_price_effective_date",
                             "2025-12-01/2025-12-15");                             // sale_price_effective_date
                }

                // 5. 库存与可售性（Required）
                String availability = mapAvailability(
                                         p.getStock().getInventoryStatus());
                item.put("availability", availability);                             // availability
                int qty = p.getStock().isTrackInventory()
                          ? (p.getStock().isInStock() ? 999 : 0)
                          : 999;
                item.put("inventory_quantity", qty);                                // inventory_quantity

                // 6. 商家信息与政策（Required；enable_checkout=true 时额外必填）
                item.put("seller_name", "Your Store");                              // seller_name
                item.put("seller_url", p.getProductPageUrl().getBase());            // seller_url
                item.put("return_policy", "https://yourshop.com/returns");         // return_policy
                item.put("return_window", 30);                                     // return_window
                item.put("seller_privacy_policy",
                         "https://yourshop.com/legal/privacy");                  // seller_privacy_policy
                item.put("seller_tos",
                         "https://yourshop.com/legal/terms");                    // seller_tos

                // 7. Flags（Required）
                item.put("enable_search", "true");                                  // enable_search
                item.put("enable_checkout", "true");                                // enable_checkout

                // 8. 配送（shipping）：示例条目，若有多条可用 List<String>
                item.put("shipping", List.of("CN:All:Standard:5.00 USD"));          // shipping

                mapped.add(item);
            }

            // 更新缓存
            cached.set(Collections.unmodifiableList(mapped));
            lastGeneratedAt = OffsetDateTime.now().toString();
            System.out.println("Product feed refreshed: " + mapped.size());
        } catch (Exception e) {
            // 生产环境请加重试与告警
            e.printStackTrace();
        }
    }

    /**
     * Controller 调用该方法返回 JSON
     */
    public Map<String, Object> getFeedResponse() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("products", cached.get());
        resp.put("generated_at", lastGeneratedAt);
        return resp;
    }

    // ======= 辅助方法 =======
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "")
                   .replaceAll("&nbsp;", " ")
                   .trim();
    }

    private String ensureHttps(String url) {
        if (url == null) return "";
        return url.startsWith("http://")
               ? url.replaceFirst("http://", "https://")
               : url;
    }

    private String mapAvailability(String status) {
        if (status == null) return "out_of_stock";
        switch (status.toUpperCase(Locale.ROOT)) {
            case "IN_STOCK": return "in_stock";
            case "OUT_OF_STOCK": return "out_of_stock";
            case "PREORDER": return "preorder";
            default: return "out_of_stock";
        }
    }

    private String safeId(String id) {
        if (id == null) return UUID.randomUUID().toString();
        return id.length() > 100 ? id.substring(0, 100) : id;
    }

    private String safeTitle(String title) {
        if (title == null || title.trim().isEmpty()) return "Untitled";
        String t = title.trim();
        return t.length() > 150 ? t.substring(0, 150) : t;
    }
}
