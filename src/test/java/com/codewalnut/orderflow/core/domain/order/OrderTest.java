package com.codewalnut.orderflow.core.domain.order;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import com.codewalnut.orderflow.core.exception.InvalidOrderStatusTransitionException;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.codewalnut.orderflow.core.service.order.OrderFactory;

class OrderTest {

    @Test
    void givenCreatedOrder_whenQueued_thenStatusBecomesQueued() {
        // Arrange
        Order order = createValidOrder("O-10");

        // Act
        order.queue();

        // Assert
        assertEquals(OrderStatus.QUEUED, order.getStatus());
    }

    @Test
    void givenNullOrBlankProductId_whenRequestedProductIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException nullProductId = assertThrows(
                InvalidOrderException.class,
                () -> new RequestedProduct(null, 1));
        InvalidOrderException blankProductId = assertThrows(
                InvalidOrderException.class,
                () -> new RequestedProduct("  ", 1));

        // Assert
        assertTrue(nullProductId.getMessage().toLowerCase().contains("product"));
        assertTrue(blankProductId.getMessage().toLowerCase().contains("product"));
    }

    @Test
    void givenBlankFields_whenOrderItemIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException blankProductId = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem(" ", "Widget", new BigDecimal("10.00"), 1));
        InvalidOrderException blankName = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem("P-1", " ", new BigDecimal("10.00"), 1));
        InvalidOrderException nonPositiveQuantity = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 0));

        // Assert
        assertTrue(blankProductId.getMessage().toLowerCase().contains("product id"));
        assertTrue(blankName.getMessage().toLowerCase().contains("product name"));
        assertTrue(nonPositiveQuantity.getMessage().toLowerCase().contains("quantity"));
    }

    @Test
    void givenQueuedOrder_whenProcessingStarts_thenStatusBecomesProcessing() {
        // Arrange
        Order order = createValidOrder("O-11");
        order.queue();

        // Act
        order.startProcessing();

        // Assert
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void givenProcessingOrder_whenCompleted_thenFinancialValuesBecomeImmutable() {
        // Arrange
        Order order = createValidOrder("O-12");
        order.queue();
        order.startProcessing();

        // Act
        order.complete(new BigDecimal("1.50"), new BigDecimal("18.50"));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(new BigDecimal("1.50"), order.getDiscountAmount().orElseThrow());
        assertEquals(new BigDecimal("18.50"), order.getFinalAmount().orElseThrow());
        assertTrue(order.getFailureReason().isEmpty());
        assertThrows(
                InvalidOrderStatusTransitionException.class,
                () -> order.complete(new BigDecimal("0.00"), new BigDecimal("20.00")));
        assertEquals(new BigDecimal("1.50"), order.getDiscountAmount().orElseThrow());
        assertEquals(new BigDecimal("18.50"), order.getFinalAmount().orElseThrow());
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void givenProcessingOrder_whenFailed_thenFailureReasonIsRecorded() {
        // Arrange
        Order order = createValidOrder("O-13");
        order.queue();
        order.startProcessing();

        // Act
        order.fail("Payment declined");

        // Assert
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals("Payment declined", order.getFailureReason().orElseThrow());
        assertTrue(order.getDiscountAmount().isEmpty());
        assertTrue(order.getFinalAmount().isEmpty());
    }

    @Test
    void givenCreatedOrQueuedOrder_whenCancelled_thenStatusBecomesCancelled() {
        // Arrange
        Order createdOrder = createValidOrder("O-14");
        Order queuedOrder = createValidOrder("O-15");
        queuedOrder.queue();

        // Act
        createdOrder.cancel();
        queuedOrder.cancel();

        // Assert
        assertEquals(OrderStatus.CANCELLED, createdOrder.getStatus());
        assertEquals(OrderStatus.CANCELLED, queuedOrder.getStatus());
    }

    @Test
    void givenUnsupportedTransition_whenAttempted_thenThrowsAndLeavesStatusUnchanged() {
        // Arrange
        Order createdOrder = createValidOrder("O-16");
        Order processingOrder = createValidOrder("O-17");
        processingOrder.queue();
        processingOrder.startProcessing();
        Order completedOrder = createValidOrder("O-18");
        completedOrder.queue();
        completedOrder.startProcessing();
        completedOrder.complete(new BigDecimal("0.00"), new BigDecimal("20.00"));

        // Act
        InvalidOrderStatusTransitionException createdToProcessing = assertThrows(
                InvalidOrderStatusTransitionException.class,
                createdOrder::startProcessing);
        assertEquals(OrderStatus.CREATED, createdOrder.getStatus());
        assertTrue(createdToProcessing.getMessage().contains("O-16"));

        InvalidOrderStatusTransitionException processingCancel = assertThrows(
                InvalidOrderStatusTransitionException.class,
                processingOrder::cancel);
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertTrue(processingOrder.getFailureReason().isEmpty());
        assertTrue(processingCancel.getMessage().contains("O-17"));

        InvalidOrderException blankFailure = assertThrows(
                InvalidOrderException.class,
                () -> processingOrder.fail(" "));
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertTrue(processingOrder.getFailureReason().isEmpty());
        assertTrue(blankFailure.getMessage().contains("blank"));

        InvalidOrderException negativeDiscount = assertThrows(
                InvalidOrderException.class,
                () -> processingOrder.complete(new BigDecimal("-1.00"), new BigDecimal("20.00")));
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertTrue(processingOrder.getDiscountAmount().isEmpty());
        assertTrue(processingOrder.getFinalAmount().isEmpty());
        assertTrue(negativeDiscount.getMessage().contains("discount amount"));

        InvalidOrderStatusTransitionException completedFail = assertThrows(
                InvalidOrderStatusTransitionException.class,
                () -> completedOrder.fail("late failure"));
        assertEquals(OrderStatus.COMPLETED, completedOrder.getStatus());
        assertEquals(new BigDecimal("0.00"), completedOrder.getDiscountAmount().orElseThrow());
        assertEquals(new BigDecimal("20.00"), completedOrder.getFinalAmount().orElseThrow());
        assertTrue(completedOrder.getFailureReason().isEmpty());
        assertTrue(completedFail.getMessage().contains("O-18"));
    }

    @Test
    void givenValidOrder_whenCreated_thenCreatedAtIsRecorded() {
        // Arrange
        Instant before = Instant.now().minusSeconds(1);

        // Act
        Order order = createValidOrder("O-19");

        // Assert
        Instant after = Instant.now().plusSeconds(1);
        assertTrue(order.getCreatedAt().isAfter(before) || order.getCreatedAt().equals(before));
        assertTrue(order.getCreatedAt().isBefore(after) || order.getCreatedAt().equals(after));
    }

    private static Order createValidOrder(String orderId) {
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
        return factory.create(
                orderId,
                new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 2))));
    }
}
