package com.example.acp.feed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class WixClient {

    @Value("${wix.api.key}")
    private String apiKey;

    @Value("${wix.site.id}")
    private String siteId;

    private static final String API_BASE = "https://www.wixapis.com";
    private final RestTemplate restTemplate = new RestTemplate();

    /** 产品列表：保持 v1 */
    public List<WixProduct> fetchProducts() {
        String url = API_BASE + "/stores/v1/products/query";
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);
        headers.set("wix-site-id", siteId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new HashMap<>();
        body.put("query", new HashMap<>());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<ProductsResponse> resp =
                    restTemplate.exchange(url, HttpMethod.POST, entity, ProductsResponse.class);
            ProductsResponse pr = resp.getBody();
            if (resp.getStatusCode() == HttpStatus.OK && pr != null && pr.getProducts() != null) {
                System.out.println(">>> products count = " + pr.getProducts().size());
                return pr.getProducts();
            }
        } catch (Exception e) {
            System.out.println(">>> fetchProducts error: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /** 变体查询：回到 stores-reader v1，并按 productId 过滤 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchVariantsByProductId(String productId) {
        String url = API_BASE + "/stores-reader/v1/variants/query";

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);
        headers.set("wix-site-id", siteId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        // v1 查询体：{ "query": { "filter": { "productId": "<id>" }, "paging": { "limit": 1000 } } }
        Map<String, Object> filter = new HashMap<>();
        filter.put("productId", productId);
        Map<String, Object> paging = new HashMap<>();
        paging.put("limit", 1000);
        Map<String, Object> query = new HashMap<>();
        query.put("filter", filter);
        query.put("paging", paging);
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
                System.out.println("[WixClient] variants non-OK: " + resp.getStatusCode());
                return Collections.emptyList();
            }
            Map<String, Object> m = resp.getBody();
            List<Map<String, Object>> variants = Collections.emptyList();

            // v1 常见返回键为 "variants"；有些环境下也可能是 "items"（双重兼容）
            Object arr = m.get("variants");
            if (arr instanceof List) {
                variants = (List<Map<String, Object>>) arr;
            } else if (m.get("items") instanceof List) {
                variants = (List<Map<String, Object>>) m.get("items");
            } else {
                System.out.println("[WixClient] no variants list, keys=" + m.keySet());
            }

            System.out.println("[WixClient] variants size for product " + productId + " = " + variants.size());
            return variants;
        } catch (Exception e) {
            System.out.println("[WixClient] fetchVariantsByProductId error: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
