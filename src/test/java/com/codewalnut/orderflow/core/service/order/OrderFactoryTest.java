package com.codewalnut.orderflow.core.service.order;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import com.codewalnut.orderflow.core.exception.InactiveProductException;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;

class OrderFactoryTest {

    @Test
    void givenDuplicateProductRequests_whenOrderIsCreated_thenQuantitiesAreCombined() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        catalog.add(
                new Product("P-2", "Gadget", "Tools", new BigDecimal("5.00"), Set.of("plastic"), 1),
                20);
        OrderFactory factory = new OrderFactory(
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
        OrderRequest request = new OrderRequest(
                "C-100",
                List.of(
                        new RequestedProduct("P-1", 2),
                        new RequestedProduct("P-2", 1),
                        new RequestedProduct("P-1", 3)));

        // Act
        Order order = factory.create("O-1", request);

        // Assert
        assertEquals(2, order.getItems().size());
        assertEquals("P-1", order.getItems().get(0).getProductId());
        assertEquals(5, order.getItems().get(0).getQuantity());
        assertEquals("P-2", order.getItems().get(1).getProductId());
        assertEquals(1, order.getItems().get(1).getQuantity());
    }

    @Test
    void givenProductPriceChangesAfterCreation_whenOrderItemIsRead_thenSnapshotIsUnchanged() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        OrderFactory factory = new OrderFactory(
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
        Order order = factory.create(
                "O-2",
                new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 2))));

        // Act
        catalog.updateDetails(
                "P-1",
                "Widget Renamed",
                "Tools",
                new BigDecimal("99.99"),
                Set.of("metal"),
                2);

        // Assert
        OrderItem item = order.getItems().getFirst();
        assertEquals("P-1", item.getProductId());
        assertEquals("Widget", item.getProductName());
        assertEquals(new BigDecimal("10.00"), item.getUnitPrice());
        assertEquals(2, item.getQuantity());
        assertEquals("Widget Renamed", catalog.findById("P-1").getName());
        assertEquals(new BigDecimal("99.99"), catalog.findById("P-1").getPrice());
    }

    @Test
    void givenValidationFailure_whenOrderIsCreated_thenNoOrderIsProduced() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                1);
        OrderFactory factory = new OrderFactory(
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
        AtomicReference<Order> createdOrder = new AtomicReference<>();

        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> createdOrder.set(factory.create(
                        "O-3",
                        new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 5))))));

        // Assert
        assertNull(createdOrder.get());
        assertTrue(exception.getMessage().contains("Available stock"));
        assertEquals(1, inventory.availableQuantity("P-1"));
    }

    @Test
    void givenValidItems_whenOrderIsCreated_thenStatusIsCreatedAndOriginalAmountIsCalculated() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.125"), Set.of("metal"), 2),
                20);
        catalog.add(
                new Product("P-2", "Gadget", "Tools", new BigDecimal("5.555"), Set.of("plastic"), 1),
                20);
        OrderFactory factory = new OrderFactory(
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

        // Act
        Order order = factory.create(
                "O-4",
                new OrderRequest(
                        "C-100",
                        List.of(
                                new RequestedProduct("P-1", 2),
                                new RequestedProduct("P-2", 1))));

        // Assert
        assertEquals("O-4", order.getId());
        assertEquals("C-100", order.getCustomerId());
        assertEquals(OrderStatus.CREATED, order.getStatus());
        assertEquals(new BigDecimal("25.82"), order.getOriginalAmount());
        assertTrue(order.getDiscountAmount().isEmpty());
        assertTrue(order.getFinalAmount().isEmpty());
        assertTrue(order.getFailureReason().isEmpty());
    }

    @Test
    void givenFactoryCreatedSnapshot_whenPublicLineTotalIsRead_thenExactTotalAndOriginalAmountRemainUnchanged() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        OrderFactory factory = new OrderFactory(
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
        Order order = factory.create(
                "O-6",
                new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 2))));
        BigDecimal originalAmountBeforeMutation = order.getOriginalAmount();

        // Act
        catalog.updateDetails(
                "P-1",
                "Widget Renamed",
                "Tools",
                new BigDecimal("99.99"),
                Set.of("metal"),
                2);
        OrderItem item = order.getItems().getFirst();

        // Assert
        assertEquals(new BigDecimal("20.00"), item.getLineTotal());
        assertEquals(new BigDecimal("10.00"), item.getUnitPrice());
        assertEquals("Widget", item.getProductName());
        assertEquals(new BigDecimal("20.00"), originalAmountBeforeMutation);
        assertEquals(originalAmountBeforeMutation, order.getOriginalAmount());
        assertEquals(new BigDecimal("99.99"), catalog.findById("P-1").getPrice());
    }

    @Test
    void givenNullOrderId_whenOrderIsCreated_thenThrowsInvalidOrderExceptionWithoutCreatingOrder() {
        // Arrange
        FactoryFixture fixture = createFactoryFixture();
        AtomicReference<Order> createdOrder = new AtomicReference<>();

        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> createdOrder.set(fixture.factory().create(null, fixture.request())));

        // Assert
        assertNull(createdOrder.get());
        assertTrue(exception.getMessage().contains("Order id"));
        assertEquals(20, fixture.inventory().availableQuantity("P-1"));
    }

    @Test
    void givenEmptyOrderId_whenOrderIsCreated_thenThrowsInvalidOrderExceptionWithoutCreatingOrder() {
        // Arrange
        FactoryFixture fixture = createFactoryFixture();
        AtomicReference<Order> createdOrder = new AtomicReference<>();

        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> createdOrder.set(fixture.factory().create("", fixture.request())));

        // Assert
        assertNull(createdOrder.get());
        assertTrue(exception.getMessage().contains("Order id"));
        assertEquals(20, fixture.inventory().availableQuantity("P-1"));
    }

    @Test
    void givenWhitespaceOrderId_whenOrderIsCreated_thenThrowsInvalidOrderExceptionWithoutCreatingOrder() {
        // Arrange
        FactoryFixture fixture = createFactoryFixture();
        AtomicReference<Order> createdOrder = new AtomicReference<>();

        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> createdOrder.set(fixture.factory().create(" \t", fixture.request())));

        // Assert
        assertNull(createdOrder.get());
        assertTrue(exception.getMessage().contains("Order id"));
        assertEquals(20, fixture.inventory().availableQuantity("P-1"));
    }

    @Test
    void givenMutableItemList_whenOrderIsCreated_thenOrderStoresImmutableCopy() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        List<RequestedProduct> mutableRequests = new ArrayList<>(List.of(
                new RequestedProduct("P-1", 2)));
        OrderRequest request = new OrderRequest("C-100", mutableRequests);
        OrderFactory factory = new OrderFactory(
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

        // Act
        Order order = factory.create("O-5", request);
        mutableRequests.add(new RequestedProduct("P-1", 99));
        List<OrderItem> items = order.getItems();

        // Assert
        assertEquals(1, items.size());
        assertEquals(2, items.getFirst().getQuantity());
        assertThrows(
                UnsupportedOperationException.class,
                () -> items.add(new OrderItem("P-x", "X", new BigDecimal("1.00"), 1)));
    }

    @Test
    void givenInactiveProduct_whenOrderIsCreated_thenThrowsInactiveProductExceptionWithoutCreatedAudit() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        AuditLog auditLog = new AuditLog();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        catalog.deactivate("P-1");
        OrderFactory factory = new OrderFactory(
                customers,
                catalog,
                inventory,
                new OrderValidationPipeline(List.of(
                        OrderValidationRule.nonEmptyRequest(),
                        OrderValidationRule.positiveQuantities(),
                        OrderValidationRule.customerExists(),
                        OrderValidationRule.productExists(),
                        OrderValidationRule.activeProducts(),
                        OrderValidationRule.availableStock())),
                auditLog);

        // Act
        InactiveProductException exception = assertThrows(
                InactiveProductException.class,
                () -> factory.create("O-INACTIVE", new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 1)))));

        // Assert
        assertTrue(exception.getMessage().contains("P-1"));
        assertTrue(auditLog.eventsFor("O-INACTIVE").isEmpty());
    }

    @Test
    void givenValidRequest_whenOrderIsCreated_thenCreatedAuditEventIsRecorded() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        AuditLog auditLog = new AuditLog();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        OrderFactory factory = new OrderFactory(
                customers,
                catalog,
                inventory,
                new OrderValidationPipeline(List.of(
                        OrderValidationRule.nonEmptyRequest(),
                        OrderValidationRule.positiveQuantities(),
                        OrderValidationRule.customerExists(),
                        OrderValidationRule.productExists(),
                        OrderValidationRule.activeProducts(),
                        OrderValidationRule.availableStock())),
                auditLog);

        // Act
        factory.create("O-CREATED", new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 1))));

        // Assert
        assertEquals(1, auditLog.eventsFor("O-CREATED").size());
        assertEquals(AuditEventType.CREATED, auditLog.eventsFor("O-CREATED").getFirst().type());
    }

    @Test
    void givenInjectedClock_whenOrderCompletesAfterUtcDayBoundary_thenOrderUsesClockForBothTimestamps() {
        // Arrange
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T23:59:59Z"));
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        OrderFactory factory = new OrderFactory(
                customers,
                catalog,
                inventory,
                new OrderValidationPipeline(List.of(
                        OrderValidationRule.nonEmptyRequest(),
                        OrderValidationRule.positiveQuantities(),
                        OrderValidationRule.customerExists(),
                        OrderValidationRule.productExists(),
                        OrderValidationRule.activeProducts(),
                        OrderValidationRule.availableStock())),
                new AuditLog(),
                clock);
        Order order = factory.create(
                "O-CLOCK",
                new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 1))));
        order.queue();
        order.startProcessing();
        clock.setInstant(Instant.parse("2026-08-24T00:00:01Z"));

        // Act
        order.complete(BigDecimal.ZERO, new BigDecimal("10.00"));

        // Assert
        assertEquals(Instant.parse("2026-08-23T23:59:59Z"), order.getCreatedAt());
        assertEquals(Instant.parse("2026-08-24T00:00:01Z"), order.getCompletedAt().orElseThrow());
    }

    private static FactoryFixture createFactoryFixture() {
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                20);
        OrderFactory factory = new OrderFactory(
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
        OrderRequest request = new OrderRequest(
                "C-100",
                List.of(new RequestedProduct("P-1", 2)));
        return new FactoryFixture(factory, inventory, request);
    }

    private record FactoryFixture(OrderFactory factory, Inventory inventory, OrderRequest request) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
