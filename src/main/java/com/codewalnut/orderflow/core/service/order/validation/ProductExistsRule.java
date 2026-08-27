package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.order.RequestedProduct;

import java.util.ArrayList;
import java.util.List;

public final class ProductExistsRule implements OrderValidationRule {
    @Override
    public ValidationResult validate(OrderValidationContext context) {
        List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
        if (requestedProducts.isEmpty()) {
            return ValidationResult.pass(PRODUCT_EXISTS);
        }
        List<String> unknownProductIds = new ArrayList<>();
        for (RequestedProduct requestedProduct : requestedProducts) {
            if (CatalogProductLookup.find(context, requestedProduct.getProductId()) == null) {
                unknownProductIds.add(requestedProduct.getProductId());
            }
        }
        if (unknownProductIds.isEmpty()) {
            return ValidationResult.pass(PRODUCT_EXISTS);
        }
        return ValidationResult.fail(
                PRODUCT_EXISTS,
                "Unknown products: " + String.join(", ", unknownProductIds));
    }
}
