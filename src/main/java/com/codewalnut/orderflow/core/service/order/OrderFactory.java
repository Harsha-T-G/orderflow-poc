package com.codewalnut.orderflow.core.service.order;

import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.exception.InactiveProductException;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationContext;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import com.codewalnut.orderflow.core.service.order.validation.ValidationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class OrderFactory {
    private final CustomerDirectory customers;
    private final ProductCatalog catalog;
    private final Inventory inventory;
    private final OrderValidationPipeline validationPipeline;
    private final AuditLog auditLog;

    public OrderFactory(
            CustomerDirectory customers,
            ProductCatalog catalog,
            Inventory inventory,
            OrderValidationPipeline validationPipeline) {
        this(customers, catalog, inventory, validationPipeline, new AuditLog());
    }

    public OrderFactory(
            CustomerDirectory customers,
            ProductCatalog catalog,
            Inventory inventory,
            OrderValidationPipeline validationPipeline,
            AuditLog auditLog) {
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.validationPipeline = Objects.requireNonNull(
                validationPipeline, "validationPipeline must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
    }

    public Order create(String orderId, OrderRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (orderId == null || orderId.isBlank()) {
            throw new InvalidOrderException("Order id must not be null or blank");
        }

        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        List<ValidationResult> results = validationPipeline.evaluate(context);
        List<ValidationResult> failures = results.stream()
                .filter(result -> !result.passed())
                .toList();
        if (!failures.isEmpty()) {
            List<ValidationResult> inactiveFailures = failures.stream()
                    .filter(failure -> OrderValidationRule.ACTIVE_PRODUCTS.equals(failure.ruleName()))
                    .toList();
            if (!inactiveFailures.isEmpty()) {
                throw new InactiveProductException(productIdsFromInactiveFailure(inactiveFailures.getFirst()));
            }
            String message = failures.stream()
                    .map(failure -> failure.ruleName() + ": " + failure.failureMessage())
                    .collect(Collectors.joining("; "));
            throw new InvalidOrderException(message);
        }

        Map<String, Integer> quantitiesByProductId = new LinkedHashMap<>();
        for (RequestedProduct requestedProduct : request.getRequestedProducts()) {
            String productId = requestedProduct.getProductId();
            int combinedQuantity = Math.addExact(
                    quantitiesByProductId.getOrDefault(productId, 0),
                    requestedProduct.getQuantity());
            quantitiesByProductId.put(productId, combinedQuantity);
        }

        List<OrderItem> items = new ArrayList<>();
        BigDecimal originalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Map.Entry<String, Integer> entry : quantitiesByProductId.entrySet()) {
            var product = catalog.findById(entry.getKey());
            OrderItem item = new OrderItem(
                    product.getId(),
                    product.getName(),
                    product.getPrice(),
                    entry.getValue());
            items.add(item);
            originalAmount = originalAmount.add(item.getLineTotal());
        }
        Order order = new Order(orderId, request.getCustomerId(), items, originalAmount, Instant.now());
        auditLog.record(orderId, AuditEventType.CREATED, "Order created");
        return order;
    }

    private static String productIdsFromInactiveFailure(ValidationResult failure) {
        String message = failure.failureMessage();
        int separator = message.indexOf(':');
        if (separator < 0 || separator == message.length() - 1) {
            return message;
        }
        return message.substring(separator + 1).trim();
    }
}
