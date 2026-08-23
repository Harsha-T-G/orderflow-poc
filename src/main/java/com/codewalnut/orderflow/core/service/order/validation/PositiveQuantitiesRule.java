package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.order.RequestedProduct;

import java.util.List;

public final class PositiveQuantitiesRule implements OrderValidationRule {
    @Override
    public ValidationResult validate(OrderValidationContext context) {
        List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
        if (requestedProducts == null) {
            return ValidationResult.pass(POSITIVE_QUANTITIES);
        }
        List<String> invalidDetails = requestedProducts.stream()
                .filter(requestedProduct -> requestedProduct.getQuantity() <= 0)
                .map(entry -> entry.getProductId() + "=" + entry.getQuantity())
                .toList();
        if (invalidDetails.isEmpty()) {
            return ValidationResult.pass(POSITIVE_QUANTITIES);
        }
        return ValidationResult.fail(
                POSITIVE_QUANTITIES,
                "Requested quantities must be positive; invalid entries: "
                        + String.join(", ", invalidDetails));
    }
}
