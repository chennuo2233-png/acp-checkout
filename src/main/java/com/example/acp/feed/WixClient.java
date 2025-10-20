package com.example.acp.feed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 调用 Wix Stores API，获取商品与变体数据。
 */
@Component
public class WixClient {

    @Value("${wix.api.key}")
    private String apiKey;

    @Value("${wix.site.id}")
    private String siteId;

    private static final String API_BASE = "https://www.wixapis.com";

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 拉取商品列表（v1 products/query）
     * 说明：保持 v1，这里已能满足列表场景。
     */
    public List<WixProduct> fetchProducts() {
        String url = API_BASE + "/stores/v1/products/query";

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);   // Wix 要求直接放 API Key
        headers.set("wix-site-id", siteId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new HashMap<>();
        body.put("query", new HashMap<>()); // 空查询，返回所有商品

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<ProductsResponse> resp =
                    restTemplate.exchange(url, HttpMethod.POST, entity, ProductsResponse.class);

            System.out.println(">>> Wix API Status (products): " + resp.getStatusCode());
            ProductsResponse responseBody = resp.getBody();
            if (resp.getStatusCode() == HttpStatus.OK
                    && responseBody != null
                    && responseBody.getProducts() != null) {
                System.out.println(">>> Fetched products count: " + responseBody.getProducts().size());
                return responseBody.getProducts();
            } else {
                System.out.println(">>> No products found or response invalid");
                return Collections.emptyList();
            }
        } catch (Exception e) {
            System.out.println(">>> Wix API request failed (products): " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按产品 ID 查询真实变体（V3：/stores/v3/products/query-variants）
     * 关键改动：
     *  - 请求体：必须使用 { "query": { "filter": {...}, "paging": {...} } } 结构
     *  - 过滤字段：productData.productId（见 Catalog V3 Read-Only Variants 的过滤支持）
     *  - 返回列表键：items（V3）；保持对旧版 variants 的兼容兜底
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchVariantsByProductId(String productId) {
        String url = API_BASE + "/stores/v3/products/query-variants";

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);
        headers.set("wix-site-id", siteId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        // --- V3 请求体：放在 "query" 包裹里 ---
        Map<String, Object> filter = new HashMap<>();
        filter.put("productData.productId", productId);          // ✅ V3 过滤字段（支持 $eq 等）
        Map<String, Object> paging = new HashMap<>();
        paging.put("limit", 1000);                               // 尽量一页拿全
        Map<String, Object> query = new HashMap<>();
        query.put("filter", filter);
        query.put("paging", paging);

        Map<String, Object> body = new HashMap<>();
        body.put("query", query);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> resp =
                    restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
                System.out.println("[WixClient] fetchVariantsByProductId non-OK: " + resp.getStatusCode());
                return Collections.emptyList();
            }

            Map<String, Object> m = resp.getBody();
            Object items = m.get("items");                        // ✅ V3 返回键
            List<Map<String, Object>> variants;
            if (items instanceof List) {
                variants = (List<Map<String, Object>>) items;
            } else if (m.get("variants") instanceof List) {       // 兼容旧版
                variants = (List<Map<String, Object>>) m.get("variants");
            } else {
                System.out.println("[WixClient] No variants list found. Keys: " + m.keySet());
                return Collections.emptyList();
            }

            System.out.println("[WixClient] variants size for product " + productId + " = " + variants.size());
            return variants;
        } catch (Exception e) {
            System.out.println("[WixClient] fetchVariantsByProductId error: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
