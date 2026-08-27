package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.order.RequestedProduct;

import java.util.List;

public final class NonEmptyRequestRule implements OrderValidationRule {
    @Override
    public ValidationResult validate(OrderValidationContext context) {
        List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
        if (requestedProducts.isEmpty()) {
            return ValidationResult.fail(
                    NON_EMPTY_REQUEST,
                    "Order request must contain at least one product");
        }
        return ValidationResult.pass(NON_EMPTY_REQUEST);
    }
}
