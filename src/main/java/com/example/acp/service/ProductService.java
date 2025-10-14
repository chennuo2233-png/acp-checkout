package com.example.acp.service;

import com.example.acp.feed.ProductFeedService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从已生成的 Product Feed 中查找指定商品的单价与币种（最小单位：分）。
 * 说明：Feed 已用 Wix 真数据构建，这里复用即可，避免重复对接 Wix。
 */
@Service
public class ProductService {

    private final ProductFeedService feedService;

    public ProductService(ProductFeedService feedService) {
        this.feedService = feedService;
    }

    /** 通过 id 或 sku 查找商品单价（分）与币种。找不到返回 empty。 */
    @SuppressWarnings("unchecked")
    public Optional<Price> findPriceById(String id) {
        Map<String, Object> feed = feedService.getFeedResponse();
        List<Map<String, Object>> items = (List<Map<String, Object>>) feed.getOrDefault("items", List.of());
        for (Map<String, Object> it : items) {
            String itemId = String.valueOf(it.get("id"));
            if (!id.equals(itemId)) continue;

            Map<String, Object> priceObj = (Map<String, Object>) it.get("price");
            if (priceObj == null) continue;

            String currency = String.valueOf(priceObj.getOrDefault("currency", "usd")).toLowerCase();
            // 兼容两种形态：amount=美元浮点 或 amount_cents=分
            long cents;
            if (priceObj.containsKey("amount_cents")) {
                cents = ((Number) priceObj.get("amount_cents")).longValue();
            } else {
                double amount = ((Number) priceObj.getOrDefault("amount", 0)).doubleValue();
                cents = Math.round(amount * 100);
            }
            return Optional.of(new Price((int) cents, currency));
        }
        return Optional.empty();
    }

    public static final class Price {
        public final int unitCents;
        public final String currency;
        public Price(int unitCents, String currency) { this.unitCents = unitCents; this.currency = currency; }
    }
}
