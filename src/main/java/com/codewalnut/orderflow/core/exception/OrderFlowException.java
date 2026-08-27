package com.codewalnut.orderflow.core.exception;

public abstract class OrderFlowException extends RuntimeException {

    protected OrderFlowException(String message) {
        super(message);
    }

    protected OrderFlowException(String message, Throwable cause) {
        super(message, cause);
    }
}
