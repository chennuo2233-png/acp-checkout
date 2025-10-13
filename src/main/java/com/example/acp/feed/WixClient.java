package com.example.acp.feed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 Wix Stores v1 products/query 接口，获取真实商品数据。
 */
@Component
public class WixClient {
    @Value("${wix.api.key}")
    private String apiKey;

    @Value("${wix.site.id}")
    private String siteId;

    private final RestTemplate restTemplate = new RestTemplate();

    public List<WixProduct> fetchProducts() {
        String url = "https://www.wixapis.com/stores/v1/products/query";

        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);                  // Bearer token
        headers.set("wix-site-id", siteId);                              // Site ID
        headers.setContentType(MediaType.APPLICATION_JSON);              // 必须声明 JSON
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        // 构造请求体：{ "query": {} }
        Map<String, Object> body = new HashMap<>();
        body.put("query", new HashMap<>());  // 空查询，返回所有商品

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<ProductsResponse> resp = restTemplate.exchange(
                url, HttpMethod.POST, entity, ProductsResponse.class);

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
}
