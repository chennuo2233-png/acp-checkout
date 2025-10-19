package com.example.acp.feed;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 Wix 原始商品映射为 OpenAI Product Feed Spec。
 * - 尽可能保留 Wix 原始字段
 * - 补齐规范要求的必填与强推荐字段
 * - 使用环境变量配置商家信息/政策/配送/默认值
 */
@Service
public class ProductFeedService {

    // ==== 商家/政策/配送/默认值（全部可用 Railway Variables 覆盖） ====
    private static final String SELLER_NAME      = getenv("SELLER_NAME", "Your Store");
    private static final String SELLER_URL       = getenv("SELLER_URL", "https://chennuo2233.wixsite.com/albertselfsaling");
    private static final String PRIVACY_URL      = getenv("PRIVACY_URL", "");
    private static final String TOS_URL          = getenv("TOS_URL", "");
    private static final String RETURNS_URL      = getenv("RETURNS_URL", "");
    private static final int    RETURN_WINDOW    = parseInt(getenv("RETURN_WINDOW_DAYS", "30"), 30);

    // 形如：US:All:Standard:10.00 USD|US:All:Express:18.00 USD
    private static final String SHIPPING_LINES   = getenv("SHIPPING_LINES", "US:All:Standard:10.00 USD");

    // 缺省品牌/材质/重量；重量需“正数+单位”，规范要求正数，这里给出合理兜底
    private static final String BRAND_DEFAULT    = getenv("BRAND_NAME", SELLER_NAME);
    private static final String MATERIAL_DEFAULT = getenv("MATERIAL_DEFAULT", "Mixed");
    private static final String WEIGHT_DEFAULT   = getenv("DEFAULT_WEIGHT", "0.2 kg");

    // 库存兜底数（当 Wix 不跟踪库存或缺少数量时）
    private static final int    DEFAULT_INVENTORY = parseInt(getenv("DEFAULT_INVENTORY", "999"), 999);

    // 当库存状态为 PREORDER 且无具体可用日期时，向后推几天（规范建议 preorder 提供 availability_date）
    private static final int    PREORDER_OFFSET_DAYS = parseInt(getenv("PREORDER_AVAIL_DAYS", "7"), 7);

    // 产品目录设置
    private static final String CATEGORY_RULES_RAW =
        System.getenv().getOrDefault("CATEGORY_RULES",
                "eyewear|glasses|眼镜=>Apparel & Accessories > Eyewear;" +
                "sweater|knit|毛衣=>Apparel & Accessories > Clothing > Outerwear & Coats > Sweaters");
private static final String DEFAULT_CATEGORY =
        System.getenv().getOrDefault("DEFAULT_CATEGORY", "Apparel & Accessories");

    // ==== 缓存最新 feed 数据 ====
    private final AtomicReference<List<Map<String, Object>>> cached =
            new AtomicReference<>(List.of());
    private volatile String lastGeneratedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();

    // 注入真实的 WixClient（带 @Component 注解）
    private final WixClient wixClient;

    public ProductFeedService(WixClient wixClient) {
        this.wixClient = wixClient;
    }

    /** 每 15 分钟刷新一次产品 feed，符合 OpenAI 的刷新建议。 */
    @Scheduled(initialDelay = 0, fixedRate = 15 * 60 * 1000)
    public void refreshFeed() {
        try {
            List<WixProduct> wixProducts = wixClient.fetchProducts();
            List<Map<String, Object>> mapped = new ArrayList<>();

            for (WixProduct p : wixProducts) {
                Map<String, Object> item = new LinkedHashMap<>();

                // ===== 1) OpenAI Flags（Required）=====
                item.put("enable_search", "true");   // lower-case string
                item.put("enable_checkout", "true"); // enable_checkout 要求 enable_search=true

                // ===== 2) Basic Product Data（Required）=====
                String id = (nonEmpty(p.getSku()) ? p.getSku() : p.getId());
                item.put("id", safeId(id));                                         // Merchant product ID
                item.put("title", safeTitle(p.getName()));                           // Title（<=150）
                item.put("description", stripHtml(p.getDescription()));              // Plain text（<=5000）
                String detailUrl = ensureHttps(buildProductUrl(p));
                item.put("link", detailUrl);                                        // Product detail URL

                // gtin（Recommended）/ mpn（gtin 缺失时 Required）
                // Wix 常无 GTIN/UPC/ISBN，使用 SKU 作为 mpn 的合理兜底
                if (nonEmpty(p.getSku())) {
                    item.put("mpn", p.getSku());
                }

                // ===== 3) Item Information（强推荐/部分 Required）=====
                item.put("condition", "new");                                        // 若非全新品，请按实际填 refurbished/used
                item.put("brand", BRAND_DEFAULT);
                item.put("material", MATERIAL_DEFAULT);
                item.put("product_category", mapCategory(p));                         // e.g. Apparel & Accessories > Eyewear
                String weight = formatWeight(p.getWeight());
                item.put("weight", (weight != null ? weight : WEIGHT_DEFAULT));

                // ===== 4) Media（Required/Optional）=====
                String mainImage = extractMainImage(p);
                if (nonEmpty(mainImage)) {
                    item.put("image_link", mainImage);                                // 主图
                }
                List<String> extraImages = extractAdditionalImages(p, mainImage);
                if (!extraImages.isEmpty()) {
                    item.put("additional_image_link", extraImages);                   // 额外图片（URL 数组）
                }

                // ===== 5) Price & Promotions（Required + Optional）=====
                Map<String, Object> price = new LinkedHashMap<>();
                if (p.getPriceData() != null) {
                    price.put("amount", p.getPriceData().getPrice());                     // 例如 7.5
                    price.put("currency", p.getPriceData().getCurrency());                // 例如 USD
                }
                item.put("price", price);

                if (p.getPriceData() != null
                        && p.getPriceData().getDiscountedPrice() != null
                        && p.getPriceData().getDiscountedPrice() < p.getPriceData().getPrice()) {
                    Map<String, Object> sale = new LinkedHashMap<>();
                    sale.put("amount", p.getPriceData().getDiscountedPrice());
                    sale.put("currency", p.getPriceData().getCurrency());
                    item.put("sale_price", sale);

                    // 可选：没有真实活动窗口时可以省略
                    String start = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    String end   = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    item.put("sale_price_effective_date", start + "/" + end);
                }

                // ===== 6) Availability & Inventory（Required）=====
                String availability = mapAvailability(p.getStock() != null ? p.getStock().getInventoryStatus() : null);
                item.put("availability", availability);                               // in_stock / out_of_stock / preorder

                if ("preorder".equals(availability)) {
                    String availDate = OffsetDateTime.now(ZoneOffset.UTC)
                            .plusDays(PREORDER_OFFSET_DAYS)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    item.put("availability_date", availDate);
                }

                int qty = DEFAULT_INVENTORY;
                if (p.getStock() != null) {
                    if (p.getStock().isTrackInventory()) {
                        qty = p.getStock().isInStock() ? DEFAULT_INVENTORY : 0;      // 没有具体数量 API，用兜底
                    } else {
                        qty = DEFAULT_INVENTORY;
                    }
                }
                item.put("inventory_quantity", Math.max(0, qty));

                // ===== 7) Variants（当存在变体时才需要 item_group_id）=====
                // 未来若 manageVariants=true，请在此补充 item_group_id / color / size 等

                // ===== 8) Fulfillment（Required where applicable）=====
                item.put("shipping", parseShippingLines(SHIPPING_LINES));

                // ===== 9) Merchant Info（Required / 当启用结账时必填）=====
                item.put("seller_name", SELLER_NAME);
                item.put("seller_url", ensureHttps(SELLER_URL));
                if (nonEmpty(RETURNS_URL)) item.put("return_policy", ensureHttps(RETURNS_URL));
                item.put("return_window", RETURN_WINDOW);
                if (nonEmpty(PRIVACY_URL)) item.put("seller_privacy_policy", ensureHttps(PRIVACY_URL));
                if (nonEmpty(TOS_URL))     item.put("seller_tos", ensureHttps(TOS_URL));

                // ===== 10) 推荐的唯一报价 ID（可帮助去重/对账，非必填）=====
                String offerId = item.get("id") + "-" +
                        (p.getPriceData() != null ? p.getPriceData().getPrice() : "0") + "-" +
                        (p.getPriceData() != null ? p.getPriceData().getCurrency() : "usd");
                item.put("offer_id", offerId);

                mapped.add(item);
            }

            cached.set(Collections.unmodifiableList(mapped));
            lastGeneratedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
            System.out.println("Product feed refreshed: " + mapped.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Controller 调用该方法返回 JSON
     * 返回结构：
     * {
     *   "generated_at": "...",
     *   "products": [ { ...feed items... } ]
     * }
     */
    public Map<String, Object> getFeedResponse() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("products", cached.get());
        resp.put("generated_at", lastGeneratedAt);
        return resp;
    }

    // ============ 辅助方法 ============

    private static String getenv(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        String text = html.replaceAll("(?is)<script.*?>.*?</script>", " ")
                          .replaceAll("(?is)<style.*?>.*?</style>", " ")
                          .replaceAll("<[^>]*>", " ")
                          .replace("&nbsp;", " ")
                          .trim();
        return text.length() > 5000 ? text.substring(0, 5000) : text;
    }

    private String ensureHttps(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("http://")) return u.replaceFirst("http://", "https://");
        return u;
    }

    private String safeId(String id) {
        if (id == null) return UUID.randomUUID().toString();
        String v = id.trim();
        return v.length() > 100 ? v.substring(0, 100) : v;
    }

    private String safeTitle(String title) {
        if (title == null || title.trim().isEmpty()) return "Untitled";
        String t = title.trim();
        return t.length() > 150 ? t.substring(0, 150) : t;
    }

    private String mapAvailability(String status) {
        if (status == null) return "out_of_stock";
        switch (status.toUpperCase(Locale.ROOT)) {
            case "IN_STOCK":      return "in_stock";
            case "OUT_OF_STOCK":  return "out_of_stock";
            case "PREORDER":      return "preorder";
            default:              return "out_of_stock";
        }
    }

    private String formatWeight(Double w) {
        if (w == null) return null;
        if (w > 0) {
            String v = (Math.round(w * 1000d) / 1000d) + " kg";
            return v.replaceAll("\\.0+ kg$", " kg");
        }
        return null;
    }

    /** 安全拼接商品详情页 URL（避免 NPE） */
    private String buildProductUrl(WixProduct p) {
        if (p == null || p.getProductPageUrl() == null) return "";
        String base = Optional.ofNullable(p.getProductPageUrl().getBase()).orElse("");
        String path = Optional.ofNullable(p.getProductPageUrl().getPath()).orElse("");
        return (base + path);
    }

    /** 提取主图（带空值保护） */
    private String extractMainImage(WixProduct p) {
        try {
            if (p.getMedia() != null
                    && p.getMedia().getMainMedia() != null
                    && p.getMedia().getMainMedia().getImage() != null) {
                return ensureHttps(p.getMedia().getMainMedia().getImage().getUrl());
            }
        } catch (Exception ignore) {}
        return "";
    }

    /** 额外图片列表（修正类型名：WixProduct.MediaInfo.MediaItem） */
    private List<String> extractAdditionalImages(WixProduct p, String main) {
        List<String> extras = new ArrayList<>();
        try {
            WixProduct.MediaInfo media = p.getMedia();
            if (media != null && media.getItems() != null) {
                for (WixProduct.MediaInfo.MediaItem mi : media.getItems()) {
                    String url = (mi != null && mi.getImage() != null) ? ensureHttps(mi.getImage().getUrl()) : null;
                    if (nonEmpty(url) && !url.equals(main)) extras.add(url);
                }
            }
        } catch (Exception ignore) {}
        return extras;
    }

    private List<String> parseShippingLines(String lines) {
        if (lines == null || lines.isBlank()) return List.of();
        String[] parts = lines.split("\\|");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    
    /** 依据标题/slug（可按需扩：tags、collections）做可配置的类目映射 */
private String mapCategory(WixProduct p) {
    String name = Optional.ofNullable(p.getName()).orElse("");
    String slug = Optional.ofNullable(p.getSlug()).orElse("");

    // 组合可搜索文本：标题 + slug（如需可加上 tags/collections）
    String text = (name + " " + slug).toLowerCase(Locale.ROOT);

    // 逐条规则匹配：左边是多个关键词用 | 分隔，右边是类目路径
    for (String rule : CATEGORY_RULES_RAW.split(";")) {
        String r = rule.trim();
        if (r.isEmpty() || !r.contains("=>")) continue;

        String[] kv = r.split("=>", 2);
        String keys = kv[0].trim().toLowerCase(Locale.ROOT);
        String categoryPath = kv[1].trim();

        // 每个规则的关键词都用 | 分隔，只要命中一个就返回该类目
        for (String k : keys.split("\\|")) {
            String key = k.trim();
            if (key.isEmpty()) continue;
            if (text.contains(key)) {
                return categoryPath;
            }
        }
    }
    // 未命中任何规则 → 兜底类目
    return DEFAULT_CATEGORY;
}
}
