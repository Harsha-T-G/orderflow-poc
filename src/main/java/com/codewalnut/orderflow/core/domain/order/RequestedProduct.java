package com.codewalnut.orderflow.core.domain.order;

import com.codewalnut.orderflow.core.exception.InvalidOrderException;

public final class RequestedProduct {
    private final String productId;
    private final int quantity;

    public RequestedProduct(String productId, int quantity) {
        if (productId == null || productId.isBlank()) {
            throw new InvalidOrderException("Product id must not be null or blank");
        }
        this.productId = productId;
        this.quantity = quantity;
    }

    public String getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
