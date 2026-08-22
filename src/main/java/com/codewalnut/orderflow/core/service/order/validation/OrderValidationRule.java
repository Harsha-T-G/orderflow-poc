package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.catalog.ProductStatus;
import com.codewalnut.orderflow.core.exception.CustomerNotFoundException;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;
import com.codewalnut.orderflow.core.exception.ProductNotFoundException;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface OrderValidationRule {
    String NON_EMPTY_REQUEST = "Non-empty request";
    String POSITIVE_QUANTITIES = "Positive quantities";
    String CUSTOMER_EXISTS = "Customer exists";
    String PRODUCT_EXISTS = "Product exists";
    String ACTIVE_PRODUCTS = "Active products";
    String AVAILABLE_STOCK = "Available stock";

    ValidationResult validate(OrderValidationContext context);

    static OrderValidationRule nonEmptyRequest() {
        return context -> {
            List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
            if (requestedProducts == null || requestedProducts.isEmpty()) {
                return ValidationResult.fail(
                        NON_EMPTY_REQUEST,
                        "Order request must contain at least one product");
            }
            return ValidationResult.pass(NON_EMPTY_REQUEST);
        };
    }

    static OrderValidationRule positiveQuantities() {
        return context -> {
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
        };
    }

    static OrderValidationRule customerExists() {
        return context -> {
            String customerId = context.getRequest().getCustomerId();
            try {
                context.getCustomers().findById(customerId);
                return ValidationResult.pass(CUSTOMER_EXISTS);
            } catch (CustomerNotFoundException exception) {
                return ValidationResult.fail(CUSTOMER_EXISTS, exception.getMessage());
            }
        };
    }

    static OrderValidationRule productExists() {
        return context -> {
            List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
            if (requestedProducts == null || requestedProducts.isEmpty()) {
                return ValidationResult.pass(PRODUCT_EXISTS);
            }
            List<String> unknownProductIds = new ArrayList<>();
            for (RequestedProduct requestedProduct : requestedProducts) {
                if (findProduct(context, requestedProduct.getProductId()) == null) {
                    unknownProductIds.add(requestedProduct.getProductId());
                }
            }
            if (unknownProductIds.isEmpty()) {
                return ValidationResult.pass(PRODUCT_EXISTS);
            }
            return ValidationResult.fail(
                    PRODUCT_EXISTS,
                    "Unknown products: " + String.join(", ", unknownProductIds));
        };
    }

    static OrderValidationRule activeProducts() {
        return context -> {
            List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
            if (requestedProducts == null || requestedProducts.isEmpty()) {
                return ValidationResult.pass(ACTIVE_PRODUCTS);
            }
            List<String> inactiveProductIds = new ArrayList<>();
            for (RequestedProduct requestedProduct : requestedProducts) {
                Product product = findProduct(context, requestedProduct.getProductId());
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
        };
    }

    static OrderValidationRule availableStock() {
        return context -> {
            List<RequestedProduct> requestedProducts = context.getRequest().getRequestedProducts();
            if (requestedProducts == null || requestedProducts.isEmpty()) {
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
        };
    }

    private static Product findProduct(OrderValidationContext context, String productId) {
        try {
            return context.getCatalog().findById(productId);
        } catch (ProductNotFoundException exception) {
            return null;
        }
    }
}
