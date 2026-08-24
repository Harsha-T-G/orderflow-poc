package com.codewalnut.orderflow.core.domain.order;

import com.codewalnut.orderflow.core.domain.MonetaryAmounts;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class OrderItem {
    private final String productId;
    private final String productName;
    private final BigDecimal unitPrice;
    private final int quantity;
    private final BigDecimal lineTotal;

    public OrderItem(String productId, String productName, BigDecimal unitPrice, int quantity) {
        if (productId == null || productId.isBlank()) {
            throw new InvalidOrderException("Order item product id must not be blank");
        }
        if (productName == null || productName.isBlank()) {
            throw new InvalidOrderException("Order item product name must not be blank");
        }
        if (quantity <= 0) {
            throw new InvalidOrderException("Order item quantity must be positive: " + quantity);
        }
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = MonetaryAmounts.requirePositive(unitPrice, "Order item unit price");
        this.quantity = quantity;
        this.lineTotal = this.unitPrice
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public String getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
