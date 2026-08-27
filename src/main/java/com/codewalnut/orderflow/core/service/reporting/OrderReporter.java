package com.codewalnut.orderflow.core.service.reporting;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
                .flatMap(order -> allocatedRevenues(order).entrySet().stream()
                        .map(entry -> Map.entry(
                                catalog.findById(entry.getKey().getProductId()).getCategory(),
                                entry.getValue())))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(
                                Map.Entry::getValue,
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
        Map<String, List<OrderLine>> itemsByProduct = completedOrders(orders)
                .flatMap(order -> allocatedRevenues(order).entrySet().stream()
                        .map(entry -> new OrderLine(entry.getKey(), entry.getValue())))
                .collect(Collectors.groupingBy(line -> line.item().getProductId()));
        return itemsByProduct.values().stream()
                .map(lines -> new ProductSales(
                        lines.getFirst().item().getProductId(),
                        lines.getFirst().item().getProductName(),
                        lines.stream().mapToInt(line -> line.item().getQuantity()).sum(),
                        lines.stream().map(OrderLine::allocatedRevenue).reduce(BigDecimal.ZERO, BigDecimal::add)))
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
                        order -> order.getCompletedAt()
                                .orElseThrow()
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate(),
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

    public CompletedOrdersPartition partitionByCompletionStatus(Collection<Order> orders) {
        Map<Boolean, List<Order>> ordersByCompletionStatus = safeOrders(orders).stream()
                .collect(Collectors.partitioningBy(order -> order.getStatus() == OrderStatus.COMPLETED));
        return new CompletedOrdersPartition(
                ordersByCompletionStatus.get(true),
                ordersByCompletionStatus.get(false));
    }

    private java.util.stream.Stream<Order> completedOrders(Collection<Order> orders) {
        return safeOrders(orders).stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED);
    }

    private Collection<Order> safeOrders(Collection<Order> orders) {
        return orders == null ? List.of() : orders;
    }

    private static Map<OrderItem, BigDecimal> allocatedRevenues(Order order) {
        List<OrderItem> items = order.getItems();
        BigInteger originalCents = cents(order.getOriginalAmount());
        BigInteger finalCents = cents(order.getFinalAmount().orElseThrow());

        if (originalCents.signum() == 0) {
            return IntStream.range(0, items.size())
                    .boxed()
                    .collect(Collectors.toMap(
                            items::get,
                            itemIndex -> amountFromCents(itemIndex == 0 ? finalCents : BigInteger.ZERO),
                            (existingAmount, duplicateAmount) -> existingAmount,
                            LinkedHashMap::new));
        }

        List<AllocationShare> allocationShares = IntStream.range(0, items.size())
                .mapToObj(itemIndex -> allocationShare(
                        itemIndex,
                        items.get(itemIndex),
                        finalCents,
                        originalCents))
                .toList();
        BigInteger allocatedCents = allocationShares.stream()
                .map(AllocationShare::floorCents)
                .reduce(BigInteger.ZERO, BigInteger::add);
        long remainingCentCount = finalCents.subtract(allocatedCents).longValueExact();
        Set<Integer> incrementedItemIndexes = allocationShares.stream()
                .sorted(Comparator.comparing(AllocationShare::remainder)
                        .reversed()
                        .thenComparingInt(AllocationShare::itemIndex))
                .limit(remainingCentCount)
                .map(AllocationShare::itemIndex)
                .collect(Collectors.toUnmodifiableSet());
        return allocationShares.stream()
                .collect(Collectors.toMap(
                        AllocationShare::item,
                        allocationShare -> amountFromCents(
                                allocationShare.floorCents().add(
                                        incrementedItemIndexes.contains(allocationShare.itemIndex())
                                                ? BigInteger.ONE
                                                : BigInteger.ZERO)),
                        (existingAmount, duplicateAmount) -> existingAmount,
                        LinkedHashMap::new));
    }

    private static BigInteger cents(BigDecimal amount) {
        return amount.movePointRight(2).toBigIntegerExact();
    }

    private static AllocationShare allocationShare(
            int itemIndex,
            OrderItem item,
            BigInteger finalCents,
            BigInteger originalCents) {
        BigInteger[] quotientAndRemainder = cents(item.getLineTotal())
                .multiply(finalCents)
                .divideAndRemainder(originalCents);
        return new AllocationShare(itemIndex, item, quotientAndRemainder[0], quotientAndRemainder[1]);
    }

    private static BigDecimal amountFromCents(BigInteger amountInCents) {
        return new BigDecimal(amountInCents, 2);
    }

    private record AllocationShare(
            int itemIndex,
            OrderItem item,
            BigInteger floorCents,
            BigInteger remainder) {
    }

    private record OrderLine(OrderItem item, BigDecimal allocatedRevenue) {
    }

    private Map<String, BigDecimal> scaleMap(Map<String, BigDecimal> totals) {
        return totals.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().setScale(2, RoundingMode.HALF_UP)));
    }
}
