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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderReporterTest {

    @Test
    void givenMixedOrderOutcomes_whenReportsRun_thenCompletedRevenueIgnoresFailedAndCancelled() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        BigDecimal revenue = reporter.completedRevenue(fixture.orders());

        // Assert
        assertEquals(new BigDecimal("95.00"), revenue);
    }

    @Test
    void givenEmptyOrders_whenReportsRun_thenResultsAreNonNullAndImmutable() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();
        List<Order> noOrders = List.of();

        // Act
        Map<OrderStatus, Long> byStatus = reporter.ordersByStatus(noOrders);
        Map<String, BigDecimal> byCategory = reporter.revenueByCategory(noOrders, fixture.catalog);
        List<String> tags = reporter.uniqueTagsAlphabetically(fixture.catalog);

        // Assert
        assertTrue(reporter.completedRevenue(noOrders).compareTo(BigDecimal.ZERO) == 0);
        assertTrue(byStatus.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> byStatus.put(OrderStatus.CREATED, 1L));
        assertTrue(byCategory.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> byCategory.put("Tools", BigDecimal.ONE));
        assertEquals(List.of("garden", "metal", "wood"), tags);
        assertThrows(UnsupportedOperationException.class, () -> tags.add("extra"));
    }

    @Test
    void givenCompletedOrders_whenGroupedAndRanked_thenCollectorsMatchSourceState() {
        // Arrange
        Fixture fixture = Fixture.sample();
        OrderReporter reporter = new OrderReporter();

        // Act
        Map<String, BigDecimal> revenueByCategory = reporter.revenueByCategory(fixture.orders(), fixture.catalog);
        Map<OrderStatus, Long> ordersByStatus = reporter.ordersByStatus(fixture.orders());
        Map<String, BigDecimal> spending = reporter.spendingByCustomer(fixture.orders());
        List<CustomerSpend> topCustomers = reporter.topFiveCustomers(fixture.orders());
        List<ProductSales> topProducts = reporter.topFiveProducts(fixture.orders());
        BigDecimal average = reporter.averageCompletedOrderValue(fixture.orders());
        Map<LocalDate, Long> byDay = reporter.completedOrdersByDay(fixture.orders());
        Map<String, Long> failures = reporter.failuresByReason(fixture.orders());
        List<Product> lowStock = reporter.lowStock(fixture.catalog);
        Map<CustomerType, Order> highestByType =
                reporter.highestValueCompletedOrderByCustomerType(fixture.orders(), fixture.customers);

        // Assert
        assertEquals(new BigDecimal("40.00"), revenueByCategory.get("Tools"));
        assertEquals(new BigDecimal("55.00"), revenueByCategory.get("Garden"));
        assertEquals(2L, ordersByStatus.get(OrderStatus.COMPLETED));
        assertEquals(1L, ordersByStatus.get(OrderStatus.FAILED));
        assertEquals(1L, ordersByStatus.get(OrderStatus.CANCELLED));
        assertEquals(new BigDecimal("40.00"), spending.get("C-REG"));
        assertEquals(new BigDecimal("55.00"), spending.get("C-PREM"));
        assertEquals("C-PREM", topCustomers.getFirst().customerId());
        assertEquals("P-1", topProducts.getFirst().productId());
        assertEquals(4, topProducts.getFirst().quantity());
        assertEquals(new BigDecimal("47.50"), average);
        assertEquals(2L, byDay.get(fixture.completedDate()));
        assertEquals(1L, failures.get("Payment failed for order F-1"));
        assertEquals("P-2", lowStock.getFirst().getId());
        assertEquals("H-PREM", highestByType.get(CustomerType.PREMIUM).getId());
        assertEquals("H-REG", highestByType.get(CustomerType.REGULAR).getId());
        assertThrows(UnsupportedOperationException.class, () -> topCustomers.add(
                new CustomerSpend("x", BigDecimal.ZERO)));
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
            catalog.add(new Product("P-1", "Hammer", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2), 50);
            catalog.add(new Product("P-2", "Rake", "Garden", new BigDecimal("55.00"), Set.of("wood", "garden"), 5), 4);
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

        LocalDate completedDate() {
            return orders.getFirst().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        }
    }
}
