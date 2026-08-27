package com.codewalnut.orderflow.core.exception;

public final class InsufficientStockException extends OrderFlowException {
    public InsufficientStockException(String productId, int requestedQuantity, int availableQuantity) {
        super("Insufficient stock for product " + productId
                + ": requested=" + requestedQuantity
                + " available=" + availableQuantity);
    }
}
