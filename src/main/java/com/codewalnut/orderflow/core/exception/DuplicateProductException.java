package com.codewalnut.orderflow.core.exception;

public final class DuplicateProductException extends OrderFlowException {
    public DuplicateProductException(String productId) {
        super("Product " + productId + " already exists");
    }
}
