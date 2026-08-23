package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.exception.CustomerNotFoundException;

public final class CustomerExistsRule implements OrderValidationRule {
    @Override
    public ValidationResult validate(OrderValidationContext context) {
        String customerId = context.getRequest().getCustomerId();
        try {
            context.getCustomers().findById(customerId);
            return ValidationResult.pass(CUSTOMER_EXISTS);
        } catch (CustomerNotFoundException exception) {
            return ValidationResult.fail(CUSTOMER_EXISTS, exception.getMessage());
        }
    }
}
