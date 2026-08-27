package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.exception.ProductNotFoundException;

final class CatalogProductLookup {
    private CatalogProductLookup() {
    }

    static Product find(OrderValidationContext context, String productId) {
        try {
            return context.getCatalog().findById(productId);
        } catch (ProductNotFoundException exception) {
            return null;
        }
    }
}
