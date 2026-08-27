package com.codewalnut.orderflow.core.service.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ProductSales(String productId, String productName, int quantity, BigDecimal revenue) {
    public ProductSales {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("Product ID must not be blank");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("Product name must not be blank");
        }
        if (revenue == null) {
            throw new IllegalArgumentException("Revenue must not be null");
        }
        revenue = revenue.setScale(2, RoundingMode.HALF_UP);
    }
}
