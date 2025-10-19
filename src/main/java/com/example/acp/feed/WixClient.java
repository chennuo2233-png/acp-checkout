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
     */
    public List<WixProduct> fetchProducts() {
        String url = API_BASE + "/stores/v1/products/query";

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);  // 与你现有写法保持一致
        headers.set("wix-site-id", siteId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new HashMap<>();
        body.put("query", new HashMap<>()); // 空查询，返回所有商品

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<ProductsResponse> resp =
                    restTemplate.exchange(url, HttpMethod.POST, entity, ProductsResponse.class);

            System.out.println(">>> Wix API Status: " + resp.getStatusCode());
            ProductsResponse responseBody = resp.getBody();
            System.out.println(">>> Wix API Body: " + responseBody);

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
            System.out.println(">>> Wix API request failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按产品 ID 查询真实变体（stores-reader v1 variants/query）。
     * 返回的每个变体 Map 常见字段：
     *  id, sku,
     *  priceData: { price, currency, discountedPrice },
     *  inventory: { inStock, quantity },
     *  choices:   { size, color, ... }
     */
    public List<Map<String, Object>> fetchVariantsByProductId(String productId) {
        String url = API_BASE + "/stores-reader/v1/variants/query";

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);  // 与 fetchProducts 保持一致（不是 Bearer 就不要改）
        headers.set("wix-site-id", siteId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new HashMap<>();
        Map<String, Object> filter = new HashMap<>();
        filter.put("productId", productId);
        body.put("filter", filter);

        Map<String, Object> paging = new HashMap<>();
        paging.put("limit", 100);
        body.put("paging", paging);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> resp =
                    restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
                System.out.println("[WixClient] fetchVariantsByProductId non-OK: " + resp.getStatusCode());
                return Collections.emptyList();
            }

            Object variantsObj = resp.getBody().get("variants");
            if (variantsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> variants = (List<Map<String, Object>>) variantsObj;
                System.out.println("[WixClient] variants size for product " + productId + " = " + variants.size());
                return variants;
            } else {
                System.out.println("[WixClient] variants field missing or not a list");
                return Collections.emptyList();
            }
        } catch (Exception e) {
            System.out.println("[WixClient] fetchVariantsByProductId error: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
