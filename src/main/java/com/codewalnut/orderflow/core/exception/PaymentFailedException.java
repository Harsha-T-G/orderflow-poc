package com.codewalnut.orderflow.core.exception;

public final class PaymentFailedException extends OrderFlowException {
    public PaymentFailedException(String orderId) {
        super("Payment failed for order " + orderId);
    }

    public PaymentFailedException(String orderId, Throwable cause) {
        super("Payment failed for order " + orderId, cause);
    }
}
