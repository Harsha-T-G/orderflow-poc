package com.codewalnut.orderflow.core.domain.catalog;

import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public final class Product {
    private final String id;
    private String name;
    private String category;
    private BigDecimal price;
    private Set<String> tags;
    private int reorderLevel;
    private ProductStatus status;

    public Product(
            String id,
            String name,
            String category,
            BigDecimal price,
            Set<String> tags,
            int reorderLevel) {
        validateId(id);
        validateName(name);
        validateCategory(category);
        validateReorderLevel(reorderLevel);
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = normalizePrice(price);
        this.tags = prepareImmutableTags(tags);
        this.reorderLevel = reorderLevel;
        this.status = ProductStatus.ACTIVE;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Set<String> getTags() {
        return tags;
    }

    public int getReorderLevel() {
        return reorderLevel;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void updateDetails(
            String name,
            String category,
            BigDecimal price,
            Set<String> tags,
            int reorderLevel) {
        validateName(name);
        validateCategory(category);
        validateReorderLevel(reorderLevel);
        BigDecimal normalizedPrice = normalizePrice(price);
        Set<String> immutableTags = prepareImmutableTags(tags);

        this.name = name;
        this.category = category;
        this.price = normalizedPrice;
        this.tags = immutableTags;
        this.reorderLevel = reorderLevel;
    }

    public void activate() {
        status = ProductStatus.ACTIVE;
    }

    public void deactivate() {
        status = ProductStatus.INACTIVE;
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new InvalidProductDataException("Product id must not be blank");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidProductDataException("Product name must not be blank");
        }
    }

    private static void validateCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new InvalidProductDataException("Product category must not be blank");
        }
    }

    private static void validateReorderLevel(int reorderLevel) {
        if (reorderLevel < 0) {
            throw new InvalidProductDataException(
                    "Product reorder level must not be negative: " + reorderLevel);
        }
    }

    private static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new InvalidMonetaryValueException("Product price must be positive: " + price);
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private static Set<String> prepareImmutableTags(Set<String> tags) {
        if (tags == null) {
            throw new InvalidProductDataException("Product tags must not be null");
        }
        for (String tag : tags) {
            if (tag == null) {
                throw new InvalidProductDataException(
                        "Product tags must not contain null elements");
            }
        }
        return Set.copyOf(tags);
    }
}
