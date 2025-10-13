package com.example.acp.feed;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProductFeedController {

    private final ProductFeedService feedService;

    public ProductFeedController(ProductFeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/product_feed")
    public Map<String, Object> getProductFeed() {
        return feedService.getFeedResponse();
    }
}
