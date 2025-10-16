package com.example.acp.service;

import com.example.acp.feed.ProductFeedService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ProductService {

    private final ProductFeedService feedService;

    public ProductService(ProductFeedService feedService) {
        this.feedService = feedService;
    }

    /** 通过商品 id 查找单价（分）+ 币种 */
    public Optional<Price> findPriceById(String id) {
        Object resp = feedService.getFeedResponse();
        if (resp == null) return Optional.empty();

        List<Map<String, Object>> items = extractItemList(resp);
        if (items.isEmpty()) return Optional.empty();

        return items.stream()
                .filter(it -> id.equals(String.valueOf(it.get("id"))))
                .findFirst()
                .flatMap(this::mapToPrice);
    }

    /** 兼容多种返回结构：List 本身 / Map→items / Map→products */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItemList(Object resp) {
        if (resp instanceof List) return (List<Map<String, Object>>) resp;

        if (resp instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) resp;
            Object items = m.getOrDefault("items", m.get("products"));
            if (items instanceof List) return (List<Map<String, Object>>) items;
        }
        return Collections.emptyList();
    }

    /** 把 item Map 映射成 Price */
    @SuppressWarnings("unchecked")
    private Optional<Price> mapToPrice(Map<String, Object> item) {
        Map<String, Object> priceObj = (Map<String, Object>) item.get("price");
        if (priceObj == null) return Optional.empty();

        double amount = ((Number) priceObj.getOrDefault("amount", 0)).doubleValue();
        long cents = Math.round(amount * 100);
        String currency = String.valueOf(priceObj.getOrDefault("currency", "usd")).toLowerCase();

        return Optional.of(new Price((int) cents, currency));
    }

    /** 值对象：单价（分）+ 币种 */
    public static final class Price {
        public final int unitCents;
        public final String currency;
        public Price(int unitCents, String currency) {
            this.unitCents = unitCents;
            this.currency = currency;
        }
    }
}
