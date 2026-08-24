package com.codewalnut.orderflow.core.service.reporting;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.order.OrderFactory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderReporterTest {

    @Test
    void givenMixedOrderOutcomes_whenCompletedRevenueIsReported_thenFailedAndCancelledOrdersAreIgnored() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        BigDecimal revenue = reporter.completedRevenue(fixture.orders());

        // Assert
        assertEquals(new BigDecimal("95.00"), revenue);
    }

    @Test
    void givenEmptyOrders_whenCompletedRevenueIsReported_thenZeroIsReturned() {
        // Arrange
        OrderReporter reporter = new OrderReporter();

        // Act
        BigDecimal completedRevenue = reporter.completedRevenue(List.of());

        // Assert
        assertEquals(new BigDecimal("0.00"), completedRevenue);
    }

    @Test
    void givenEmptyOrders_whenRevenueByCategoryIsReported_thenResultIsEmptyAndImmutable() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<String, BigDecimal> revenueByCategory = reporter.revenueByCategory(List.of(), fixture.catalog);

        // Assert
        assertTrue(revenueByCategory.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> revenueByCategory.put("Tools", BigDecimal.ONE));
    }

    @Test
    void givenEmptyOrders_whenOrdersByStatusIsReported_thenResultIsEmptyAndImmutable() {
        // Arrange
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<OrderStatus, Long> ordersByStatus = reporter.ordersByStatus(List.of());

        // Assert
        assertTrue(ordersByStatus.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> ordersByStatus.put(OrderStatus.CREATED, 1L));
    }

    @Test
    void givenCompletedOrders_whenRevenueByCategoryIsReported_thenCategoryTotalsAreImmutable() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<String, BigDecimal> revenueByCategory = reporter.revenueByCategory(fixture.orders(), fixture.catalog);

        // Assert
        assertEquals(Map.of(
                "Tools", new BigDecimal("40.00"),
                "Garden", new BigDecimal("55.00")), revenueByCategory);
        assertThrows(UnsupportedOperationException.class,
                () -> revenueByCategory.put("Other", BigDecimal.ZERO));
    }

    @Test
    void givenMixedOrderOutcomes_whenOrdersByStatusIsReported_thenEveryStatusIsCounted() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<OrderStatus, Long> ordersByStatus = reporter.ordersByStatus(fixture.orders());

        // Assert
        assertEquals(2L, ordersByStatus.get(OrderStatus.COMPLETED));
        assertEquals(1L, ordersByStatus.get(OrderStatus.FAILED));
        assertEquals(1L, ordersByStatus.get(OrderStatus.CANCELLED));
    }

    @Test
    void givenCompletedOrders_whenSpendingByCustomerIsReported_thenFinalAmountsAreGroupedByCustomer() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<String, BigDecimal> spendingByCustomer = reporter.spendingByCustomer(fixture.orders());

        // Assert
        assertEquals(Map.of(
                "C-REG", new BigDecimal("40.00"),
                "C-PREM", new BigDecimal("55.00")), spendingByCustomer);
    }

    @Test
    void givenCompletedOrders_whenTopCustomersAreReported_thenCustomersAreRankedBySpend() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        List<CustomerSpend> topCustomers = reporter.topFiveCustomers(fixture.orders());

        // Assert
        assertEquals(List.of(
                new CustomerSpend("C-PREM", new BigDecimal("55.00")),
                new CustomerSpend("C-REG", new BigDecimal("40.00"))), topCustomers);
        assertThrows(UnsupportedOperationException.class,
                () -> topCustomers.add(new CustomerSpend("C-OTHER", BigDecimal.ZERO)));
    }

    @Test
    void givenCompletedOrders_whenTopProductsAreReported_thenProductsAreRankedByQuantity() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        List<ProductSales> topProducts = reporter.topFiveProducts(fixture.orders());

        // Assert
        assertEquals("P-1", topProducts.getFirst().productId());
        assertEquals(4, topProducts.getFirst().quantity());
        assertEquals(new BigDecimal("40.00"), topProducts.getFirst().revenue());
    }

    @Test
    void givenCompletedOrders_whenAverageValueIsReported_thenAverageUsesCompletedFinalAmounts() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        BigDecimal averageCompletedOrderValue = reporter.averageCompletedOrderValue(fixture.orders());

        // Assert
        assertEquals(new BigDecimal("47.50"), averageCompletedOrderValue);
    }

    @Test
    void givenCompletedOrderCreatedBeforeUtcMidnight_whenCompletedAfterUtcMidnight_thenCompletionDayIsUsed() {
        // Arrange
        Instant createdAt = Instant.parse("2026-08-23T23:59:59Z");
        Instant completedAt = Instant.parse("2026-08-24T00:00:01Z");
        Order order = new Order(
                "H-CROSS-DAY",
                "C-REG",
                List.of(new com.codewalnut.orderflow.core.domain.order.OrderItem(
                        "P-1", "Hammer", new BigDecimal("10.00"), 1)),
                new BigDecimal("10.00"),
                createdAt,
                Clock.fixed(completedAt, ZoneOffset.UTC));
        order.queue();
        order.startProcessing();
        order.complete(BigDecimal.ZERO, new BigDecimal("10.00"));
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<LocalDate, Long> completedOrdersByDay = reporter.completedOrdersByDay(List.of(order));

        // Assert
        assertEquals(Map.of(LocalDate.parse("2026-08-24"), 1L), completedOrdersByDay);
    }

    @Test
    void givenFailedOrder_whenFailuresByReasonIsReported_thenFailureReasonIsCounted() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<String, Long> failuresByReason = reporter.failuresByReason(fixture.orders());

        // Assert
        assertEquals(Map.of("Payment failed for order F-1", 1L), failuresByReason);
    }

    @Test
    void givenCatalogWithLowStock_whenLowStockIsReported_thenProductsAreSortedByQuantity() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        List<Product> lowStockProducts = reporter.lowStock(fixture.catalog);

        // Assert
        assertEquals(List.of("P-2"), lowStockProducts.stream().map(Product::getId).toList());
    }

    @Test
    void givenCatalogTagsWithMixedCase_whenUniqueTagsAreReported_thenTagsAreNormalizedAndImmutable() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        List<String> uniqueTags = reporter.uniqueTagsAlphabetically(fixture.catalog);

        // Assert
        assertEquals(List.of("garden", "metal", "wood"), uniqueTags);
        assertThrows(UnsupportedOperationException.class, () -> uniqueTags.add("extra"));
    }

    @Test
    void givenCompletedOrders_whenHighestValueByCustomerTypeIsReported_thenHighestOrderIsSelectedPerType() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<CustomerType, Order> highestOrders = reporter.highestValueCompletedOrderByCustomerType(
                fixture.orders(), fixture.customers);

        // Assert
        assertEquals("H-PREM", highestOrders.get(CustomerType.PREMIUM).getId());
        assertEquals("H-REG", highestOrders.get(CustomerType.REGULAR).getId());
    }

    @Test
    void givenMixedOrderOutcomes_whenPartitionedByCompletionStatus_thenBothBucketsContainMatchingOrders() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        CompletedOrdersPartition partition = reporter.partitionByCompletionStatus(fixture.orders());

        // Assert
        assertEquals(2, partition.completedOrders().size());
        assertEquals(2, partition.nonCompletedOrders().size());
        assertTrue(partition.completedOrders().stream()
                .allMatch(order -> order.getStatus() == OrderStatus.COMPLETED));
        assertTrue(partition.nonCompletedOrders().stream()
                .noneMatch(order -> order.getStatus() == OrderStatus.COMPLETED));
    }

    @Test
    void givenMixedOrderOutcomes_whenPartitionedByCompletionStatus_thenBothReturnedBucketsAreImmutable() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        CompletedOrdersPartition partition = reporter.partitionByCompletionStatus(fixture.orders());

        // Assert
        assertThrows(
                UnsupportedOperationException.class,
                () -> partition.completedOrders().add(fixture.orders().getFirst()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> partition.nonCompletedOrders().add(fixture.orders().getFirst()));
    }

    @Test
    void givenMutableOrderBuckets_whenCompletedPartitionIsCreated_thenBothBucketsAreDefensivelyCopied() {
        // Arrange
        Fixture fixture = Fixture.sample();
        List<Order> completedOrders = new ArrayList<>(fixture.orders().subList(0, 2));
        List<Order> nonCompletedOrders = new ArrayList<>(fixture.orders().subList(2, 4));

        // Act
        CompletedOrdersPartition partition = new CompletedOrdersPartition(completedOrders, nonCompletedOrders);
        completedOrders.clear();
        nonCompletedOrders.clear();

        // Assert
        assertEquals(2, partition.completedOrders().size());
        assertEquals(2, partition.nonCompletedOrders().size());
    }

    @Test
    void givenDiscountedCompletedOrder_whenRevenueByCategoryIsReported_thenCategoryMatchesFinalAmount() {
        // Arrange
        Fixture fixture = Fixture.sample();
        Order discounted = fixture.completedDiscountedPremium();
        OrderReporter reporter = new OrderReporter();
        List<Order> orders = List.of(discounted);

        // Act
        Map<String, BigDecimal> byCategory = reporter.revenueByCategory(orders, fixture.catalog);

        // Assert
        assertEquals(new BigDecimal("52.25"), byCategory.get("Garden"));
    }

    @Test
    void givenThreeEqualTenCentItems_whenRevenueByCategoryIsReported_thenLargestRemainderUsesItemOrderForTies() {
        // Arrange
        Fixture fixture = Fixture.sample();
        Order discounted = fixture.completedDiscountedMultiItem();
        OrderReporter reporter = new OrderReporter();
        List<Order> orders = List.of(discounted);
        Map<String, BigDecimal> expectedRevenueByCategory = Map.of(
                "Tools", new BigDecimal("0.18"),
                "Garden", new BigDecimal("0.10"));

        // Act
        Map<String, BigDecimal> revenueByCategory = reporter.revenueByCategory(orders, fixture.catalog);

        // Assert
        assertEquals(expectedRevenueByCategory, revenueByCategory);
    }

    @Test
    void givenThreeEqualTenCentItems_whenTopProductsAreReported_thenLargestRemainderUsesItemOrderForTies() {
        // Arrange
        Fixture fixture = Fixture.sample();
        Order discounted = fixture.completedDiscountedMultiItem();
        OrderReporter reporter = new OrderReporter();
        List<Order> orders = List.of(discounted);
        Map<String, BigDecimal> expectedProductRevenue = Map.of(
                "P-A", new BigDecimal("0.09"),
                "P-B", new BigDecimal("0.09"),
                "P-C", new BigDecimal("0.10"));

        // Act
        List<ProductSales> topProducts = reporter.topFiveProducts(orders);

        // Assert
        assertEquals(expectedProductRevenue, productRevenuesById(topProducts));
    }

    @Test
    void givenCorporateOrderWithTenEqualOneCentItems_whenRevenueByCategoryIsReported_thenEightCentsAreNonnegativeAndExact() {
        // Arrange
        Fixture fixture = Fixture.sample();
        Order discounted = fixture.completedCorporateTenItemOrder();
        OrderReporter reporter = new OrderReporter();
        Map<String, BigDecimal> expectedCategoryRevenue = new LinkedHashMap<>();
        for (int productNumber = 1; productNumber <= 10; productNumber++) {
            expectedCategoryRevenue.put(
                    "Category-" + productNumber,
                    productNumber <= 8 ? new BigDecimal("0.01") : new BigDecimal("0.00"));
        }

        // Act
        Map<String, BigDecimal> revenueByCategory = reporter.revenueByCategory(
                List.of(discounted), fixture.catalog);

        // Assert
        assertEquals(expectedCategoryRevenue, revenueByCategory);
        assertTrue(revenueByCategory.values().stream().allMatch(amount -> amount.signum() >= 0));
        assertEquals(new BigDecimal("0.08"), revenueByCategory.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void givenCorporateOrderWithTenEqualOneCentItems_whenTopProductsAreReported_thenExactAllocatedAmountsAreReturned() {
        // Arrange
        Fixture fixture = Fixture.sample();
        Order discounted = fixture.completedCorporateTenItemOrder();
        OrderReporter reporter = new OrderReporter();
        Map<String, BigDecimal> expectedProductRevenue = Map.of(
                "P-01", new BigDecimal("0.01"),
                "P-02", new BigDecimal("0.01"),
                "P-03", new BigDecimal("0.01"),
                "P-04", new BigDecimal("0.01"),
                "P-05", new BigDecimal("0.01"));

        // Act
        List<ProductSales> topProducts = reporter.topFiveProducts(List.of(discounted));

        // Assert
        assertEquals(expectedProductRevenue, productRevenuesById(topProducts));
        assertTrue(topProducts.stream().allMatch(productSales -> productSales.revenue().signum() >= 0));
    }

    private static Map<String, BigDecimal> productRevenuesById(List<ProductSales> productSales) {
        return productSales.stream().collect(java.util.stream.Collectors.toMap(
                ProductSales::productId,
                ProductSales::revenue));
    }

    private static final class Fixture {
        private final Inventory inventory = new Inventory();
        private final ProductCatalog catalog = new ProductCatalog(inventory);
        private final CustomerDirectory customers = new CustomerDirectory();
        private final OrderFactory factory;
        private final List<Order> orders;

        private Fixture() {
            customers.register(new Customer("C-REG", "Reg Buyer", "reg@example.com", CustomerType.REGULAR));
            customers.register(new Customer("C-PREM", "Prem Buyer", "prem@example.com", CustomerType.PREMIUM));
            customers.register(new Customer(
                    "C-CORP", "Corporate Buyer", "corporate@example.com", CustomerType.CORPORATE));
            catalog.add(new Product("P-1", "Hammer", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2), 50);
            catalog.add(new Product("P-2", "Rake", "Garden", new BigDecimal("55.00"), Set.of("wood", "garden"), 5), 4);
            catalog.add(new Product("P-A", "Nail", "Tools", new BigDecimal("0.10"), Set.of("metal"), 1), 50);
            catalog.add(new Product("P-B", "Washer", "Tools", new BigDecimal("0.10"), Set.of("metal"), 1), 50);
            catalog.add(new Product("P-C", "Pin", "Garden", new BigDecimal("0.10"), Set.of("garden"), 1), 50);
            for (int productNumber = 1; productNumber <= 10; productNumber++) {
                String productId = "P-%02d".formatted(productNumber);
                catalog.add(new Product(
                        productId,
                        "Corporate item " + productNumber,
                        "Category-" + productNumber,
                        new BigDecimal("0.01"),
                        Set.of(),
                        0), 50);
            }
            factory = new OrderFactory(
                    customers,
                    catalog,
                    inventory,
                    new OrderValidationPipeline(List.of(
                            OrderValidationRule.nonEmptyRequest(),
                            OrderValidationRule.positiveQuantities(),
                            OrderValidationRule.customerExists(),
                            OrderValidationRule.productExists(),
                            OrderValidationRule.activeProducts(),
                            OrderValidationRule.availableStock())));
            Order completedRegular = factory.create(
                    "H-REG",
                    new OrderRequest("C-REG", List.of(new RequestedProduct("P-1", 4))));
            completedRegular.queue();
            completedRegular.startProcessing();
            completedRegular.complete(new BigDecimal("0.00"), new BigDecimal("40.00"));
            Order completedPremium = factory.create(
                    "H-PREM",
                    new OrderRequest("C-PREM", List.of(new RequestedProduct("P-2", 1))));
            completedPremium.queue();
            completedPremium.startProcessing();
            completedPremium.complete(new BigDecimal("2.75"), new BigDecimal("55.00"));
            Order failed = factory.create(
                    "F-1",
                    new OrderRequest("C-REG", List.of(new RequestedProduct("P-1", 1))));
            failed.queue();
            failed.startProcessing();
            failed.fail("Payment failed for order F-1");
            Order cancelled = factory.create(
                    "X-1",
                    new OrderRequest("C-REG", List.of(new RequestedProduct("P-1", 1))));
            cancelled.cancel();
            this.orders = List.of(completedRegular, completedPremium, failed, cancelled);
        }

        static Fixture sample() {
            return new Fixture();
        }

        List<Order> orders() {
            return orders;
        }

        Order completedDiscountedPremium() {
            Order order = factory.create(
                    "H-DISC",
                    new OrderRequest("C-PREM", List.of(new RequestedProduct("P-2", 1))));
            order.queue();
            order.startProcessing();
            order.complete(new BigDecimal("2.75"), new BigDecimal("52.25"));
            return order;
        }

        Order completedDiscountedMultiItem() {
            Order order = factory.create(
                    "H-MULTI",
                    new OrderRequest("C-PREM", List.of(
                            new RequestedProduct("P-C", 1),
                            new RequestedProduct("P-A", 1),
                            new RequestedProduct("P-B", 1))));
            order.queue();
            order.startProcessing();
            order.complete(new BigDecimal("0.02"), new BigDecimal("0.28"));
            return order;
        }

        Order completedCorporateTenItemOrder() {
            List<RequestedProduct> requestedProducts = java.util.stream.IntStream.rangeClosed(1, 10)
                    .mapToObj(productNumber -> new RequestedProduct(
                            "P-%02d".formatted(productNumber), 1))
                    .toList();
            Order order = factory.create(
                    "H-CORPORATE",
                    new OrderRequest("C-CORP", requestedProducts));
            order.queue();
            order.startProcessing();
            order.complete(new BigDecimal("0.02"), new BigDecimal("0.08"));
            return order;
        }
    }
}
