package com.codewalnut.orderflow;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;

import java.math.BigDecimal;
import java.util.Set;

final class OrderFlowCatalogSeed {

    static final int PRODUCT_COUNT = 15;
    static final int CONTENDED_PRODUCT_QUANTITY = 5;
    static final int DEFAULT_PRODUCT_QUANTITY = 40;
    private static final String[] CATEGORIES = {"Tools", "Garden", "Kitchen", "Sports"};

    private OrderFlowCatalogSeed() {
    }

    static void seed(ProductCatalog catalog) {
        for (int productIndex = 1; productIndex <= PRODUCT_COUNT; productIndex++) {
            String category = CATEGORIES[(productIndex - 1) % CATEGORIES.length];
            int initialQuantity = productIndex == 1
                    ? CONTENDED_PRODUCT_QUANTITY
                    : DEFAULT_PRODUCT_QUANTITY;
            catalog.add(
                    new Product(
                            productId(productIndex),
                            "Product " + productIndex,
                            category,
                            new BigDecimal(productIndex + ".99"),
                            Set.of(category.toLowerCase(), "demo"),
                            3),
                    initialQuantity);
        }
    }

    static String productId(int sequence) {
        return "P-" + String.format("%02d", sequence);
    }
}
