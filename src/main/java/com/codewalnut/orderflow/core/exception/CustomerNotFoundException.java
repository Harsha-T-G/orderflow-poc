package com.codewalnut.orderflow.core.exception;

public final class CustomerNotFoundException extends OrderFlowException {

    public CustomerNotFoundException(String customerId) {
        super("Customer " + customerId + " was not found");
    }
}
