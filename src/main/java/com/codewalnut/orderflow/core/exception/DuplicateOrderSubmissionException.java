package com.codewalnut.orderflow.core.exception;

public final class DuplicateOrderSubmissionException extends OrderFlowException {
    public DuplicateOrderSubmissionException(String orderId) {
        super("Order " + orderId + " has already been submitted");
    }
}
