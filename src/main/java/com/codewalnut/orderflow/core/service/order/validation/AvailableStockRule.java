package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AvailableStockRule implements OrderValidationRule {
    @Override
    public ValidationResult validate(OrderValidationContext context) {
        List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
        if (requestedProducts.isEmpty()) {
            return ValidationResult.pass(AVAILABLE_STOCK);
        }
        Map<String, Integer> requestedQuantitiesByProductId = new LinkedHashMap<>();
        Set<String> overflowedProductIds = new HashSet<>();
        List<String> quantityOverflowDetails = new ArrayList<>();
        for (RequestedProduct requestedProduct : requestedProducts) {
            String productId = requestedProduct.getProductId();
            if (overflowedProductIds.contains(productId)) {
                continue;
            }
            int requestedQuantity = requestedProduct.getQuantity();
            Integer aggregatedQuantity = requestedQuantitiesByProductId.get(productId);
            if (aggregatedQuantity == null) {
                requestedQuantitiesByProductId.put(productId, requestedQuantity);
                continue;
            }
            try {
                requestedQuantitiesByProductId.put(
                        productId,
                        Math.addExact(aggregatedQuantity, requestedQuantity));
            } catch (ArithmeticException exception) {
                overflowedProductIds.add(productId);
                quantityOverflowDetails.add(
                        productId + " quantities=" + aggregatedQuantity + "+" + requestedQuantity);
                requestedQuantitiesByProductId.remove(productId);
            }
        }
        if (!quantityOverflowDetails.isEmpty()) {
            return ValidationResult.fail(
                    AVAILABLE_STOCK,
                    "Requested quantity overflow for products: "
                            + String.join(", ", quantityOverflowDetails));
        }
        List<String> insufficientStockDetails = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : requestedQuantitiesByProductId.entrySet()) {
            String productId = entry.getKey();
            int requestedQuantity = entry.getValue();
            try {
                int availableQuantity = context.getInventory().availableQuantity(productId);
                if (availableQuantity < requestedQuantity) {
                    insufficientStockDetails.add(
                            productId + " requested=" + requestedQuantity
                                    + " available=" + availableQuantity);
                }
            } catch (InvalidProductDataException exception) {
                insufficientStockDetails.add(
                        productId + " requested=" + requestedQuantity + " available=unavailable");
            }
        }
        if (insufficientStockDetails.isEmpty()) {
            return ValidationResult.pass(AVAILABLE_STOCK);
        }
        return ValidationResult.fail(
                AVAILABLE_STOCK,
                "Insufficient available stock for products: "
                        + String.join(", ", insufficientStockDetails));
    }
}
