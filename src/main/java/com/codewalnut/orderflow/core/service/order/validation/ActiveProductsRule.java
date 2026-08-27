package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.catalog.ProductStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;

import java.util.ArrayList;
import java.util.List;

public final class ActiveProductsRule implements OrderValidationRule {
    @Override
    public ValidationResult validate(OrderValidationContext context) {
        List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
        if (requestedProducts.isEmpty()) {
            return ValidationResult.pass(ACTIVE_PRODUCTS);
        }
        List<String> inactiveProductIds = new ArrayList<>();
        for (RequestedProduct requestedProduct : requestedProducts) {
            Product product = CatalogProductLookup.find(context, requestedProduct.getProductId());
            if (product != null && product.getStatus() != ProductStatus.ACTIVE) {
                inactiveProductIds.add(requestedProduct.getProductId());
            }
        }
        if (inactiveProductIds.isEmpty()) {
            return ValidationResult.pass(ACTIVE_PRODUCTS);
        }
        return ValidationResult.fail(
                ACTIVE_PRODUCTS,
                "Inactive products cannot be ordered: " + String.join(", ", inactiveProductIds));
    }
}
