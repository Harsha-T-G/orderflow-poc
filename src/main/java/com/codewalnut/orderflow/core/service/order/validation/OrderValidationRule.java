package com.codewalnut.orderflow.core.service.order.validation;

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
        return new NonEmptyRequestRule();
    }

    static OrderValidationRule positiveQuantities() {
        return new PositiveQuantitiesRule();
    }

    static OrderValidationRule customerExists() {
        return new CustomerExistsRule();
    }

    static OrderValidationRule productExists() {
        return new ProductExistsRule();
    }

    static OrderValidationRule activeProducts() {
        return new ActiveProductsRule();
    }

    static OrderValidationRule availableStock() {
        return new AvailableStockRule();
    }
}
