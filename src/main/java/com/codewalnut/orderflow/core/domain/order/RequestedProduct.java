package com.codewalnut.orderflow.core.domain.order;

public final class RequestedProduct {
    private final String productId;
    private final int quantity;

    public RequestedProduct(String productId, int quantity) {
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
