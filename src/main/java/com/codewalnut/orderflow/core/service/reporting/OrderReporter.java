package com.codewalnut.orderflow.core.service.reporting;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class OrderReporter {

    public BigDecimal completedRevenue(Collection<Order> orders) {
        return completedOrders(orders)
                .map(order -> order.getFinalAmount().orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, BigDecimal> revenueByCategory(Collection<Order> orders, ProductCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Map<String, BigDecimal> totals = completedOrders(orders)
                .flatMap(order -> order.getItems().stream())
                .collect(Collectors.groupingBy(
                        item -> catalog.findById(item.getProductId()).getCategory(),
                        Collectors.mapping(
                                OrderItem::getLineTotal,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        return Map.copyOf(scaleMap(totals));
    }

    public Map<OrderStatus, Long> ordersByStatus(Collection<Order> orders) {
        return Map.copyOf(safeOrders(orders).stream()
                .collect(Collectors.groupingBy(Order::getStatus, Collectors.counting())));
    }

    public Map<String, BigDecimal> spendingByCustomer(Collection<Order> orders) {
        Map<String, BigDecimal> totals = completedOrders(orders)
                .collect(Collectors.groupingBy(
                        Order::getCustomerId,
                        Collectors.mapping(
                                order -> order.getFinalAmount().orElse(BigDecimal.ZERO),
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        return Map.copyOf(scaleMap(totals));
    }

    public List<CustomerSpend> topFiveCustomers(Collection<Order> orders) {
        return spendingByCustomer(orders).entrySet().stream()
                .map(entry -> new CustomerSpend(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CustomerSpend::amount).reversed()
                        .thenComparing(CustomerSpend::customerId))
                .limit(5)
                .toList();
    }

    public List<ProductSales> topFiveProducts(Collection<Order> orders) {
        Map<String, List<OrderItem>> itemsByProduct = completedOrders(orders)
                .flatMap(order -> order.getItems().stream())
                .collect(Collectors.groupingBy(OrderItem::getProductId));
        return itemsByProduct.values().stream()
                .map(items -> new ProductSales(
                        items.getFirst().getProductId(),
                        items.getFirst().getProductName(),
                        items.stream().mapToInt(OrderItem::getQuantity).sum(),
                        items.stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .sorted(Comparator.comparingInt(ProductSales::quantity).reversed()
                        .thenComparing(ProductSales::productId))
                .limit(5)
                .toList();
    }

    public BigDecimal averageCompletedOrderValue(Collection<Order> orders) {
        return completedOrders(orders)
                .map(order -> order.getFinalAmount().orElse(BigDecimal.ZERO))
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        amounts -> amounts.isEmpty()
                                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                                : amounts.stream()
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP)));
    }

    public Map<LocalDate, Long> completedOrdersByDay(Collection<Order> orders) {
        return Map.copyOf(completedOrders(orders)
                .collect(Collectors.groupingBy(
                        order -> order.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        Collectors.counting())));
    }

    public Map<String, Long> failuresByReason(Collection<Order> orders) {
        return Map.copyOf(safeOrders(orders).stream()
                .filter(order -> order.getStatus() == OrderStatus.FAILED)
                .collect(Collectors.groupingBy(
                        order -> order.getFailureReason().orElse("unknown"),
                        Collectors.counting())));
    }

    public List<Product> lowStock(ProductCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        return catalog.findLowStockProducts();
    }

    public List<String> uniqueTagsAlphabetically(ProductCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        return catalog.sortedByName().stream()
                .flatMap(product -> product.getTags().stream())
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    public Map<CustomerType, Order> highestValueCompletedOrderByCustomerType(
            Collection<Order> orders,
            CustomerDirectory customers) {
        Objects.requireNonNull(customers, "customers must not be null");
        return Map.copyOf(completedOrders(orders)
                .collect(Collectors.groupingBy(
                        order -> customers.findById(order.getCustomerId()).getType(),
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparing(
                                        (Order order) -> order.getFinalAmount().orElse(BigDecimal.ZERO))),
                                optionalOrder -> optionalOrder.orElseThrow()))));
    }

    public Map<Boolean, List<Order>> completedVersusOther(Collection<Order> orders) {
        return Map.copyOf(safeOrders(orders).stream()
                .collect(Collectors.partitioningBy(order -> order.getStatus() == OrderStatus.COMPLETED)));
    }

    private java.util.stream.Stream<Order> completedOrders(Collection<Order> orders) {
        return safeOrders(orders).stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED);
    }

    private Collection<Order> safeOrders(Collection<Order> orders) {
        return orders == null ? List.of() : orders;
    }

    private Map<String, BigDecimal> scaleMap(Map<String, BigDecimal> totals) {
        return totals.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().setScale(2, RoundingMode.HALF_UP)));
    }
}
