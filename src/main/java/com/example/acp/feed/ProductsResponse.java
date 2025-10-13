package com.example.acp.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 用于反序列化 Wix Stores API 返回的根对象，
 * 只关注 products 数组，忽略其它未知属性。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductsResponse {
    private List<WixProduct> products;

    public List<WixProduct> getProducts() {
        return products;
    }

    public void setProducts(List<WixProduct> products) {
        this.products = products;
    }

    @Override
    public String toString() {
        return "ProductsResponse{products=" + products + "}";
    }
}
