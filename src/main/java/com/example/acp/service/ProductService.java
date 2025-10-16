package com.example.acp.service;

import com.example.acp.feed.ProductFeedService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductFeedService feedService;

    public ProductService(ProductFeedService feedService) {
        this.feedService = feedService;
    }

    /** 通过商品 id 查找单价（分）+ 币种 */
    public Optional<Price> findPriceById(String id) {
        // feedResponse 就是 List<Map<String,Object>>
        Object resp = feedService.getFeedResponse();
        if (!(resp instanceof List)) return Optional.empty();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp;

        return items.stream()
                .filter(it -> id.equals(String.valueOf(it.get("id"))))
                .findFirst()
                .flatMap(this::mapToPrice);
    }

    /** 把 item Map 映射成 Price 对象 */
    private Optional<Price> mapToPrice(Map<String, Object> item) {
        @SuppressWarnings("unchecked")
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
