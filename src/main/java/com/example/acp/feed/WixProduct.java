package com.example.acp.feed;

import java.util.List;

// 对应 Wix Stores API 返回的 products 数组中单个 product 对象
public class WixProduct {
    // 基本字段
    private String id;
    private String name;
    private String slug;
    private boolean visible;
    private String productType;
    private String description;
    private String sku;
    private Double weight;
    
    // 嵌套对象
    private StockInfo stock;
    private PriceData priceData;
    private MediaInfo media;
    private PageUrl productPageUrl;
    
    // getters & setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }

    public StockInfo getStock() { return stock; }
    public void setStock(StockInfo stock) { this.stock = stock; }

    public PriceData getPriceData() { return priceData; }
    public void setPriceData(PriceData priceData) { this.priceData = priceData; }

    public MediaInfo getMedia() { return media; }
    public void setMedia(MediaInfo media) { this.media = media; }

    public PageUrl getProductPageUrl() { return productPageUrl; }
    public void setProductPageUrl(PageUrl productPageUrl) { this.productPageUrl = productPageUrl; }

    // 嵌套类：库存信息
    public static class StockInfo {
        private boolean trackInventory;
        private boolean inStock;
        private String inventoryStatus;

        public boolean isTrackInventory() { return trackInventory; }
        public void setTrackInventory(boolean trackInventory) { this.trackInventory = trackInventory; }

        public boolean isInStock() { return inStock; }
        public void setInStock(boolean inStock) { this.inStock = inStock; }

        public String getInventoryStatus() { return inventoryStatus; }
        public void setInventoryStatus(String inventoryStatus) { this.inventoryStatus = inventoryStatus; }
    }

    // 嵌套类：价格数据
    public static class PriceData {
        private String currency;
        private Double price;
        private Double discountedPrice;

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }

        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }

        public Double getDiscountedPrice() { return discountedPrice; }
        public void setDiscountedPrice(Double discountedPrice) { this.discountedPrice = discountedPrice; }
    }

    // 嵌套类：媒体信息
    public static class MediaInfo {
        private MainMedia mainMedia;
        private List<MediaItem> items;

        public MainMedia getMainMedia() { return mainMedia; }
        public void setMainMedia(MainMedia mainMedia) { this.mainMedia = mainMedia; }

        public List<MediaItem> getItems() { return items; }
        public void setItems(List<MediaItem> items) { this.items = items; }

        public static class MainMedia {
            private Image image;
            public Image getImage() { return image; }
            public void setImage(Image image) { this.image = image; }
        }

        public static class MediaItem {
            private Image image;
            public Image getImage() { return image; }
            public void setImage(Image image) { this.image = image; }
        }

        public static class Image {
            private String url;
            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
        }
    }

    // 嵌套类：产品页面 URL
    public static class PageUrl {
        private String base;
        private String path;

        public String getBase() { return base; }
        public void setBase(String base) { this.base = base; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
}
