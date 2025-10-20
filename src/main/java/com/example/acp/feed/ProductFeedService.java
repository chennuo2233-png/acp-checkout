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

    // 例：US:All:Standard:10.00 USD|US:All:Express:18.00 USD
    private static final String SHIPPING_LINES   = getenv("SHIPPING_LINES", "US:All:Standard:10.00 USD");

    // 缺省品牌/材质/重量；重量需“正数+单位”
    private static final String BRAND_DEFAULT    = getenv("BRAND_NAME", SELLER_NAME);
    private static final String MATERIAL_DEFAULT = getenv("MATERIAL_DEFAULT", "Mixed");
    private static final String WEIGHT_DEFAULT   = getenv("DEFAULT_WEIGHT", "0.2 kg");

    // 库存兜底数（当 Wix 不跟踪库存或缺少数量时）
    private static final int    DEFAULT_INVENTORY = parseInt(getenv("DEFAULT_INVENTORY", "999"), 999);

    // 当库存状态为 PREORDER 且无具体可用日期时，向后推几天
    private static final int    PREORDER_OFFSET_DAYS = parseInt(getenv("PREORDER_AVAIL_DAYS", "7"), 7);

    // 产品目录映射规则（关键词 => 类目路径）
    private static final String CATEGORY_RULES_RAW =
            System.getenv().getOrDefault("CATEGORY_RULES",
                    "eyewear|glasses|眼镜=>Apparel & Accessories > Eyewear;"
                  + "sweater|knit|毛衣=>Apparel & Accessories > Clothing > Outerwear & Coats > Sweaters");
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
                // ---------- 先准备“基底 item”（除 id/offer_id/变体专属字段） ----------
                Map<String, Object> base = new LinkedHashMap<>();

                // Flags
                base.put("enable_search", "true");
                base.put("enable_checkout", "true");

                // Basic
                String parentId = nonEmpty(p.getSku()) ? p.getSku() : p.getId();
                String baseTitleStr = safeTitle(p.getName());
                base.put("title", baseTitleStr);
                base.put("description", stripHtml(p.getDescription()));
                String detailUrl = ensureHttps(buildProductUrl(p));
                base.put("link", detailUrl);
                if (nonEmpty(p.getSku())) base.put("mpn", p.getSku());

                // Item info
                base.put("condition", "new");
                base.put("brand", BRAND_DEFAULT);
                base.put("material", MATERIAL_DEFAULT);
                base.put("product_category", mapCategory(p));
                String weightStr = formatWeight(p.getWeight());
                base.put("weight", (weightStr != null ? weightStr : WEIGHT_DEFAULT));

                // Media
                String mainImageUrl = extractMainImage(p);
                if (nonEmpty(mainImageUrl)) base.put("image_link", mainImageUrl);
                List<String> extraImages = extractAdditionalImages(p, mainImageUrl);
                if (!extraImages.isEmpty()) base.put("additional_image_link", extraImages);

                // Price（父级）
                Map<String, Object> basePriceMap = new LinkedHashMap<>();
                if (p.getPriceData() != null) {
                    basePriceMap.put("amount", p.getPriceData().getPrice());
                    basePriceMap.put("currency", p.getPriceData().getCurrency());
                }
                base.put("price", basePriceMap);

                if (p.getPriceData() != null
                        && p.getPriceData().getDiscountedPrice() != null
                        && p.getPriceData().getDiscountedPrice() < p.getPriceData().getPrice()) {
                    Map<String, Object> sale = new LinkedHashMap<>();
                    sale.put("amount", p.getPriceData().getDiscountedPrice());
                    sale.put("currency", p.getPriceData().getCurrency());
                    base.put("sale_price", sale);

                    String start = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    String end   = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    base.put("sale_price_effective_date", start + "/" + end);
                }

                // Availability & inventory（父级兜底）
                String availabilityStr = mapAvailability(p.getStock() != null ? p.getStock().getInventoryStatus() : null);
                int invQty = DEFAULT_INVENTORY;
                if (p.getStock() != null) {
                    if (p.getStock().isTrackInventory()) {
                        invQty = p.getStock().isInStock() ? DEFAULT_INVENTORY : 0;
                    } else {
                        invQty = DEFAULT_INVENTORY;
                    }
                }
                base.put("availability", availabilityStr);
                if ("preorder".equals(availabilityStr)) {
                    String availDate = OffsetDateTime.now(ZoneOffset.UTC)
                            .plusDays(PREORDER_OFFSET_DAYS)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    base.put("availability_date", availDate);
                }
                base.put("inventory_quantity", Math.max(0, invQty));

                // Fulfillment
                base.put("shipping", parseShippingLines(SHIPPING_LINES));

                // Merchant & returns
                base.put("seller_name", SELLER_NAME);
                base.put("seller_url", ensureHttps(SELLER_URL));
                if (nonEmpty(RETURNS_URL)) base.put("return_policy", ensureHttps(RETURNS_URL));
                base.put("return_window", RETURN_WINDOW);
                if (nonEmpty(PRIVACY_URL)) base.put("seller_privacy_policy", ensureHttps(PRIVACY_URL));
                if (nonEmpty(TOS_URL))     base.put("seller_tos", ensureHttps(TOS_URL));

                // ===== 变体逻辑：先尝试真实变体，其次兜底 Size，最后才输出父级单品 =====
                boolean wroteVariant = false;
                boolean hasOptions = (p.getProductOptions() != null && !p.getProductOptions().isEmpty());

                if (p.isManageVariants() && hasOptions) {
                    // 拉取真实变体
                    List<Map<String, Object>> realVariants = wixClient.fetchVariantsByProductId(p.getId());

                    // 解析 choices → 签名（判定是否有可区分属性）
                    Set<String> signatures = new LinkedHashSet<>();
                    List<Map<String, String>> allChoicePairs = new ArrayList<>();
                    for (Map<String, Object> v : realVariants) {
                        Map<String, String> pairs = extractChoicePairsFromVariant(v);
                        allChoicePairs.add(pairs);
                        signatures.add(buildChoiceSignature(pairs));
                    }
                    boolean allEmptyOrSame = signatures.isEmpty()
                            || (signatures.size() == 1 && (signatures.iterator().next().isEmpty()));

                    if (!realVariants.isEmpty() && !allEmptyOrSame) {
                        // 真的有可区分的选项：按变体展开（只要写出了任意变体，就不再输出父级行）
                        String groupId = safeId(p.getId());
                        Set<String> seenSig = new HashSet<>();

                        for (int i = 0; i < realVariants.size(); i++) {
                            Map<String, Object> v = realVariants.get(i);
                            Map<String, String> pairs = allChoicePairs.get(i);
                            String sig = buildChoiceSignature(pairs);

                            if (sig.isEmpty()) continue;  // 跳过没有区分属性的“伪变体”
                            if (!seenSig.add(sig)) continue; // 去重：同签名只保留一条

                            Map<String, Object> variant = new LinkedHashMap<>(base);

                            // 变体 ID / SKU
                            String variantObjId = (v.get("id") != null ? String.valueOf(v.get("id")) : null);
                            String variantSku   = (v.get("sku") != null ? String.valueOf(v.get("sku")) : null);
                            if (variantSku != null && !variantSku.isBlank()) {
                                variant.put("mpn", variantSku); // 无 GTIN 时，用 mpn 满足“id/gtin/mpn 之一”
                            }

                            // 覆盖价格（若有）
                            
                            // 1) 先尝试从 priceData 取
                            Double vPrice = num(v, "priceData", "price");
                            String vCurr  = str(v, "priceData", "currency");
                            Double vSale  = num(v, "priceData", "discountedPrice");
                            
                            // 2) 取不到就看 convertedPriceData
                            if (vPrice == null) { vPrice = num(v, "convertedPriceData", "price"); }
                            if (vCurr  == null) { vCurr  = str(v, "convertedPriceData", "currency"); }
                            if (vSale  == null) { vSale  = num(v, "convertedPriceData", "discountedPrice"); }
                            
                            // 3) 还没有就看 price（有些返回用这个键）
                            if (vPrice == null) { vPrice = num(v, "price", "price"); }
                            if (vCurr  == null) { vCurr  = str(v, "price", "currency"); }
                            if (vSale  == null) { vSale  = num(v, "price", "discountedPrice"); }
                            
                            // 4) 成功拿到则覆盖父级 price
                            if (vPrice != null && vCurr != null) {
                                Map<String, Object> variantPrice = new LinkedHashMap<>();
                                variantPrice.put("amount", vPrice);
                                variantPrice.put("currency", vCurr);
                                variant.put("price", variantPrice);
                                
                                if (vSale != null && vSale < vPrice) {
                                    Map<String, Object> sale = new LinkedHashMap<>();
                                    sale.put("amount", vSale);
                                    sale.put("currency", vCurr);
                                    variant.put("sale_price", sale);
    
                                }
                            }

                            // 覆盖库存（若有）
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inv = (Map<String, Object>) v.get("inventory");
                            if (inv != null) {
                                Boolean inStock = asBool(inv.get("inStock"));
                                Integer qtyVar  = asInt(inv.get("quantity"));
                                if (inStock != null) {
                                    variant.put("availability", inStock ? "in_stock" : "out_of_stock");
                                    variant.put("inventory_quantity", inStock ? (qtyVar != null ? qtyVar : DEFAULT_INVENTORY) : 0);
                                }
                            }

                            // 区分属性（size / color / 其他）
                            if (!pairs.isEmpty()) {
                                for (Map.Entry<String, String> e : pairs.entrySet()) {
                                    String k = e.getKey();
                                    String vs = e.getValue();
                                    if ("size".equals(k)) {
                                        variant.put("size", vs);
                                    } else if ("color".equals(k)) {
                                        variant.put("color", vs);
                                    } else {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> attrs = (Map<String, Object>) variant.getOrDefault("attributes", new LinkedHashMap<>());
                                        attrs.put(k, vs);
                                        variant.put("attributes", attrs);
                                    }
                                }
                            }

                            // 分组与最终 ID/offer_id
                            variant.put("item_group_id", groupId);
                            variant.put("item_group_title", baseTitleStr);
                            String idPart = (variantSku != null && !variantSku.isBlank())
                                    ? variantSku
                                    : (variantObjId != null ? variantObjId : ("v-" + UUID.randomUUID()));
                            variant.put("id", safeId(parentId + "-" + idPart));

                            @SuppressWarnings("unchecked")
                            Map<String, Object> pr = (Map<String, Object>) variant.get("price");
                            String offerId = variant.get("id") + "-" +
                                    (pr != null ? String.valueOf(pr.get("amount")) : "0") + "-" +
                                    (pr != null ? String.valueOf(pr.get("currency")) : "usd");
                            variant.put("offer_id", offerId);

                            mapped.add(variant);
                        }

                        wroteVariant = true;
                        // 关键：一旦写出变体，不再输出父级行
                        continue;
                    }
                }

                // ---------- 若没有真实变体：尝试“按 Size 兜底展开”；成功就不再输出父级 ----------
                List<String> sizeChoices = extractSizeChoices(p);
                if (!wroteVariant && sizeChoices != null && !sizeChoices.isEmpty()) {
                    String groupId = safeId(p.getId());
                    for (String size : sizeChoices) {
                        Map<String, Object> variant = new LinkedHashMap<>(base);
                        String variantId = safeId(parentId + "-sz-" + slug(size));
                        variant.put("id", variantId);
                        variant.put("item_group_id", groupId);
                        variant.put("item_group_title", baseTitleStr);
                        variant.put("size", size);

                        String offerId = variantId + "-" +
                                (p.getPriceData() != null ? p.getPriceData().getPrice() : "0") + "-" +
                                (p.getPriceData() != null ? p.getPriceData().getCurrency() : "usd");
                        variant.put("offer_id", offerId);

                        mapped.add(variant);
                    }
                    wroteVariant = true;
                    continue; // 兜底展开后也不再输出父级行
                }

                // ---------- 到这里仍未写出任何变体 ⇒ 输出父级单品 ----------
                if (!wroteVariant) {
                    Map<String, Object> single = new LinkedHashMap<>(base);
                    single.put("id", safeId(parentId));
                    String offerId = single.get("id") + "-" +
                            (p.getPriceData() != null ? p.getPriceData().getPrice() : "0") + "-" +
                            (p.getPriceData() != null ? p.getPriceData().getCurrency() : "usd");
                    single.put("offer_id", offerId);
                    mapped.add(single);
                }
            }

            cached.set(Collections.unmodifiableList(mapped));
            lastGeneratedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
            System.out.println("Product feed refreshed (with variants): " + mapped.size());
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
            case "IN_STOCK":     return "in_stock";
            case "OUT_OF_STOCK": return "out_of_stock";
            case "PREORDER":     return "preorder";
            default:             return "out_of_stock";
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

    /** 额外图片列表 */
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

    /** 解析配送行（多条用 | 分隔） */
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

        String text = (name + " " + slug).toLowerCase(Locale.ROOT);

        for (String rule : CATEGORY_RULES_RAW.split(";")) {
            String r = rule.trim();
            if (r.isEmpty() || !r.contains("=>")) continue;

            String[] kv = r.split("=>", 2);
            String keys = kv[0].trim().toLowerCase(Locale.ROOT);
            String categoryPath = kv[1].trim();

            for (String k : keys.split("\\|")) {
                String key = k.trim();
                if (key.isEmpty()) continue;
                if (text.contains(key)) {
                    return categoryPath;
                }
            }
        }
        return DEFAULT_CATEGORY;
    }

    /** 提取 Size 选项（兜底用；真实变体优先） */
    private List<String> extractSizeChoices(WixProduct p) {
        List<String> out = new ArrayList<>();
        if (p == null || p.getProductOptions() == null) return out;
        for (WixProduct.ProductOption opt : p.getProductOptions()) {
            if (opt == null || opt.getName() == null) continue;
            String name = opt.getName().trim().toLowerCase(Locale.ROOT);
            if (!name.equals("size") && !name.equals("尺寸") && !name.equals("尺码")) continue;
            if (opt.getChoices() == null) continue;
            for (WixProduct.ProductOption.OptionChoice ch : opt.getChoices()) {
                if (ch != null && ch.getValue() != null && !ch.getValue().trim().isEmpty()) {
                    out.add(ch.getValue().trim());
                }
            }
        }
        return out;
    }

    /** 把 "Large Tall" 变成 "large-tall" 这样的安全片段用于 variantId */
    private String slug(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9]+", "-");
        t = t.replaceAll("^-+|-+$", "");
        return t.isEmpty() ? "na" : t;
    }

    /* ---------- 安全取值（基础类型） ---------- */
    private Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }
    private Integer asInt(Object o) {
        Double d = asDouble(o);
        return d == null ? null : d.intValue();
    }
    private Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return null;
    }

    /* ---------- 安全取值（沿路径） ---------- */
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(k);
            if (cur == null) return null;
        }
        return (Map<String, Object>) cur;
    }
    private String str(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
            if (cur == null) return null;
        }
        return String.valueOf(cur);
    }
    private Double num(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
            if (cur == null) return null;
        }
        if (cur instanceof Number) return ((Number) cur).doubleValue();
        try { return Double.valueOf(String.valueOf(cur)); } catch (Exception e) { return null; }
    }
    private Integer intVal(Map<String, Object> m, String... path) {
        Double n = num(m, path);
        return n == null ? null : n.intValue();
    }
    private Boolean bool(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
            if (cur == null) return null;
        }
        if (cur instanceof Boolean) return (Boolean) cur;
        if (cur instanceof String) return Boolean.parseBoolean((String) cur);
        return null;
    }

    /* ---------- 规格归一化与解析 ---------- */
    /** 规格名归一化：size/color（含中文“尺寸/尺码/颜色/顏色”）；其他统一为小写、空白转下划线 */
    private String normalizeOptionName(String k) {
        if (k == null) return "";
        String raw = k.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("size") || "尺寸".equals(raw) || "尺码".equals(raw)) return "size";
        if (lower.contains("color") || "颜色".equals(raw) || "顏色".equals(raw)) return "color";
        String t = lower.replaceAll("\\s+", "_").replaceAll("^_+|_+$", "");
        return t;
    }

    /** 解析变体 choices：兼容 Map 与 List 两种结构，输出为标准键名（size/color/…），中文同样归一化 */
    @SuppressWarnings("unchecked")
    private Map<String, String> extractChoicePairsFromVariant(Map<String, Object> v) {
        Map<String, String> out = new LinkedHashMap<>();
        if (v == null) return out;
        Object choicesObj = v.get("choices");
        if (choicesObj == null) return out;

        if (choicesObj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) choicesObj;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String normKey = normalizeOptionName(String.valueOf(e.getKey()).trim());
                String val = String.valueOf(e.getValue()).trim();
                if (!normKey.isEmpty() && !val.isEmpty()) out.put(normKey, val);
            }
        } else if (choicesObj instanceof List) {
            List<?> arr = (List<?>) choicesObj;
            for (Object o : arr) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> one = (Map<String, Object>) o;

                String rawName = null;
                if (one.get("name") != null) rawName = String.valueOf(one.get("name"));
                else if (one.get("option") != null) rawName = String.valueOf(one.get("option"));
                else if (one.get("optionName") != null) rawName = String.valueOf(one.get("optionName"));

                String rawVal = null;
                if (one.get("value") != null) rawVal = String.valueOf(one.get("value"));
                else if (one.get("selection") != null) rawVal = String.valueOf(one.get("selection"));
                else if (one.get("choice") != null) rawVal = String.valueOf(one.get("choice"));

                if (rawName == null || rawVal == null) continue;
                String normKey = normalizeOptionName(rawName.trim());
                String val = rawVal.trim();
                if (!normKey.isEmpty() && !val.isEmpty()) out.put(normKey, val);
            }
        }
        return out;
    }

    /** 把 choices 生成签名，用于判断“是否有区分属性”和去重；例如 size=small|color=green */
    private String buildChoiceSignature(Map<String, String> pairs) {
        if (pairs == null || pairs.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        pairs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> parts.add(e.getKey() + "=" + e.getValue().toLowerCase(Locale.ROOT)));
        return String.join("|", parts);
    }
}
