package com.codewalnut.orderflow.core.exception;

public final class ProductNotFoundException extends OrderFlowException {
    public ProductNotFoundException(String productId) {
        super("Product " + productId + " was not found");
    }
}
