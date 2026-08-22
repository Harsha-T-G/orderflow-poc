package com.codewalnut.orderflow.core.exception;

public final class InactiveProductException extends OrderFlowException {
    public InactiveProductException(String productId) {
        super("Product " + productId + " is inactive and cannot be processed");
    }
}
