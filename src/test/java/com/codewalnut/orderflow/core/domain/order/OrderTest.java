package com.codewalnut.orderflow.core.domain.order;

import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import com.codewalnut.orderflow.core.exception.InvalidOrderStatusTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderTest {

    @Test
    void givenNullOrderId_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        null,
                        "C-100",
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        new BigDecimal("10.00"),
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("order id"));
    }

    @Test
    void givenBlankOrderId_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        " ",
                        "C-100",
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        new BigDecimal("10.00"),
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("order id"));
    }

    @Test
    void givenBlankCustomerId_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        "O-1",
                        " ",
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        new BigDecimal("10.00"),
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("customer id"));
    }

    @Test
    void givenNullCustomerId_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        "O-1",
                        null,
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        new BigDecimal("10.00"),
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("customer id"));
    }

    @Test
    void givenNullItems_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        "O-1",
                        "C-100",
                        null,
                        BigDecimal.ZERO,
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("item"));
    }

    @Test
    void givenEmptyItems_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        "O-1",
                        "C-100",
                        List.of(),
                        BigDecimal.ZERO,
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("item"));
    }

    @Test
    void givenNegativeOriginalAmount_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        "O-1",
                        "C-100",
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        new BigDecimal("-0.01"),
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("original amount"));
    }

    @Test
    void givenNullOriginalAmount_whenOrderIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new Order(
                        "O-1",
                        "C-100",
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        null,
                        Instant.parse("2026-08-24T10:00:00Z")));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("original amount"));
    }

    @Test
    void givenMutableItems_whenOrderIsCreated_thenStoresImmutableCopy() {
        // Arrange
        List<OrderItem> items = new ArrayList<>(
                List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)));
        Order order = new Order(
                "O-1",
                "C-100",
                items,
                new BigDecimal("10.00"),
                Instant.parse("2026-08-24T10:00:00Z"));

        // Act
        items.add(new OrderItem("P-2", "Gadget", new BigDecimal("5.00"), 1));

        // Assert
        assertEquals(1, order.getItems().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> order.getItems().add(new OrderItem("P-3", "Tool", BigDecimal.ONE, 1)));
    }

    @Test
    void givenNullClock_whenOrderIsCreated_thenThrowsNullPointerException() {
        // Act
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new Order(
                        "O-1",
                        "C-100",
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        new BigDecimal("10.00"),
                        Instant.parse("2026-08-24T10:00:00Z"),
                        null));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("clock"));
    }

    @Test
    void givenNullCreatedAt_whenOrderIsCreated_thenThrowsNullPointerException() {
        // Act
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new Order(
                        "O-1",
                        "C-100",
                        List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 1)),
                        new BigDecimal("10.00"),
                        null));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("createdat"));
    }

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
    void givenNullProductId_whenRequestedProductIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new RequestedProduct(null, 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("product"));
    }

    @Test
    void givenBlankProductId_whenRequestedProductIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new RequestedProduct("  ", 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("product"));
    }

    @Test
    void givenBlankProductId_whenOrderItemIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem(" ", "Widget", new BigDecimal("10.00"), 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("product id"));
    }

    @Test
    void givenNullProductId_whenOrderItemIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem(null, "Widget", new BigDecimal("10.00"), 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("product id"));
    }

    @Test
    void givenBlankProductName_whenOrderItemIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem("P-1", " ", new BigDecimal("10.00"), 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("product name"));
    }

    @Test
    void givenNullProductName_whenOrderItemIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem("P-1", null, new BigDecimal("10.00"), 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("product name"));
    }

    @Test
    void givenNullUnitPrice_whenOrderItemIsCreated_thenThrowsInvalidMonetaryValueException() {
        // Act
        InvalidMonetaryValueException exception = assertThrows(
                InvalidMonetaryValueException.class,
                () -> new OrderItem("P-1", "Widget", null, 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("unit price"));
    }

    @Test
    void givenNegativeUnitPrice_whenOrderItemIsCreated_thenThrowsInvalidMonetaryValueException() {
        // Act
        InvalidMonetaryValueException exception = assertThrows(
                InvalidMonetaryValueException.class,
                () -> new OrderItem("P-1", "Widget", new BigDecimal("-0.01"), 1));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("unit price"));
    }

    @Test
    void givenZeroQuantity_whenOrderItemIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 0));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("quantity"));
    }

    @Test
    void givenMutableRequestedProducts_whenOrderRequestIsCreated_thenStoresImmutableCopy() {
        // Arrange
        List<RequestedProduct> requestedProducts = new ArrayList<>(
                List.of(new RequestedProduct("P-1", 1)));
        OrderRequest request = new OrderRequest("C-100", requestedProducts);

        // Act
        requestedProducts.add(new RequestedProduct("P-2", 1));

        // Assert
        assertEquals(1, request.getRequestedProducts().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.getRequestedProducts().add(new RequestedProduct("P-3", 1)));
    }

    @Test
    void givenNullCustomerId_whenOrderRequestIsCreated_thenThrowsInvalidOrderException() {
        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new OrderRequest(null, List.of(new RequestedProduct("P-1", 1))));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("customer id"));
    }

    @Test
    void givenNullRequestedProducts_whenOrderRequestIsCreated_thenStoresEmptyImmutableList() {
        // Act
        OrderRequest request = new OrderRequest("C-100", null);

        // Assert
        assertTrue(request.getRequestedProducts().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.getRequestedProducts().add(new RequestedProduct("P-1", 1)));
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
    void givenProcessingOrder_whenCompleted_thenRecordsFinancialValuesAndStatus() {
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
    }

    @Test
    void givenProcessingOrderWithInjectedClock_whenCompleted_thenCompletedAtUsesClockInstant() {
        // Arrange
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T23:59:59Z"));
        Order order = createValidOrder("O-12-CLOCK", clock);
        order.queue();
        order.startProcessing();
        clock.setInstant(Instant.parse("2026-08-24T00:00:01Z"));

        // Act
        order.complete(new BigDecimal("1.50"), new BigDecimal("18.50"));

        // Assert
        assertEquals(Instant.parse("2026-08-24T00:00:01Z"), order.getCompletedAt().orElseThrow());
    }

    @Test
    void givenCompletedOrder_whenCompletionIsAttemptedAgain_thenCompletionTimeRemainsUnchanged() {
        // Arrange
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T00:00:01Z"));
        Order order = createValidOrder("O-12-IMMUTABLE", clock);
        order.queue();
        order.startProcessing();
        order.complete(new BigDecimal("1.50"), new BigDecimal("18.50"));
        clock.setInstant(Instant.parse("2026-08-25T00:00:01Z"));

        // Act
        InvalidOrderStatusTransitionException exception = assertThrows(
                InvalidOrderStatusTransitionException.class,
                () -> order.complete(new BigDecimal("0.00"), new BigDecimal("20.00")));

        // Assert
        assertTrue(exception.getMessage().contains("O-12-IMMUTABLE"));
        assertEquals(Instant.parse("2026-08-24T00:00:01Z"), order.getCompletedAt().orElseThrow());
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
    void givenCreatedOrder_whenCancelled_thenStatusBecomesCancelled() {
        // Arrange
        Order createdOrder = createValidOrder("O-14");

        // Act
        createdOrder.cancel();

        // Assert
        assertEquals(OrderStatus.CANCELLED, createdOrder.getStatus());
    }

    @Test
    void givenQueuedOrder_whenCancelled_thenStatusBecomesCancelled() {
        // Arrange
        Order queuedOrder = createValidOrder("O-15");
        queuedOrder.queue();

        // Act
        queuedOrder.cancel();

        // Assert
        assertEquals(OrderStatus.CANCELLED, queuedOrder.getStatus());
    }

    @Test
    void givenCreatedOrder_whenProcessingStarts_thenThrowsAndLeavesStatusUnchanged() {
        // Arrange
        Order createdOrder = createValidOrder("O-16");

        // Act
        InvalidOrderStatusTransitionException exception = assertThrows(
                InvalidOrderStatusTransitionException.class,
                createdOrder::startProcessing);

        // Assert
        assertEquals(OrderStatus.CREATED, createdOrder.getStatus());
        assertTrue(exception.getMessage().contains("O-16"));
    }

    @Test
    void givenProcessingOrder_whenCancelled_thenThrowsAndLeavesStatusUnchanged() {
        // Arrange
        Order processingOrder = createValidOrder("O-17");
        processingOrder.queue();
        processingOrder.startProcessing();

        // Act
        InvalidOrderStatusTransitionException exception = assertThrows(
                InvalidOrderStatusTransitionException.class,
                processingOrder::cancel);

        // Assert
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertTrue(processingOrder.getFailureReason().isEmpty());
        assertTrue(exception.getMessage().contains("O-17"));
    }

    @Test
    void givenProcessingOrder_whenFailedWithBlankReason_thenThrowsAndLeavesStateUnchanged() {
        // Arrange
        Order processingOrder = createValidOrder("O-17-BLANK");
        processingOrder.queue();
        processingOrder.startProcessing();

        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> processingOrder.fail(" "));

        // Assert
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertTrue(processingOrder.getFailureReason().isEmpty());
        assertTrue(exception.getMessage().contains("blank"));
    }

    @Test
    void givenProcessingOrder_whenCompletedWithNegativeDiscount_thenThrowsWithoutCompletionTime() {
        // Arrange
        Order processingOrder = createValidOrder("O-17-DISCOUNT");
        processingOrder.queue();
        processingOrder.startProcessing();

        // Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> processingOrder.complete(new BigDecimal("-1.00"), new BigDecimal("20.00")));

        // Assert
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertTrue(processingOrder.getDiscountAmount().isEmpty());
        assertTrue(processingOrder.getFinalAmount().isEmpty());
        assertTrue(processingOrder.getCompletedAt().isEmpty());
        assertTrue(exception.getMessage().contains("discount amount"));
    }

    @Test
    void givenCompletedOrder_whenFailed_thenThrowsAndLeavesStateUnchanged() {
        // Arrange
        Order completedOrder = createValidOrder("O-18");
        completedOrder.queue();
        completedOrder.startProcessing();
        completedOrder.complete(new BigDecimal("0.00"), new BigDecimal("20.00"));

        // Act
        InvalidOrderStatusTransitionException exception = assertThrows(
                InvalidOrderStatusTransitionException.class,
                () -> completedOrder.fail("late failure"));

        // Assert
        assertEquals(OrderStatus.COMPLETED, completedOrder.getStatus());
        assertEquals(new BigDecimal("0.00"), completedOrder.getDiscountAmount().orElseThrow());
        assertEquals(new BigDecimal("20.00"), completedOrder.getFinalAmount().orElseThrow());
        assertTrue(completedOrder.getFailureReason().isEmpty());
        assertTrue(exception.getMessage().contains("O-18"));
    }

    @Test
    void givenValidOrder_whenCreated_thenCreatedAtIsRecorded() {
        // Arrange
        Instant createdAt = Instant.parse("2026-08-24T10:00:00Z");
        Clock clock = Clock.fixed(createdAt, ZoneOffset.UTC);

        // Act
        Order order = createValidOrder("O-19", clock);

        // Assert
        assertEquals(createdAt, order.getCreatedAt());
    }

    private static Order createValidOrder(String orderId) {
        return createValidOrder(orderId, Clock.systemUTC());
    }

    private static Order createValidOrder(String orderId, Clock clock) {
        return new Order(
                orderId,
                "C-100",
                List.of(new OrderItem("P-1", "Widget", new BigDecimal("10.00"), 2)),
                new BigDecimal("20.00"),
                clock.instant(),
                clock);
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
