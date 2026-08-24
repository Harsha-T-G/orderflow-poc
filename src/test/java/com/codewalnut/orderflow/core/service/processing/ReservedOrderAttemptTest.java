package com.codewalnut.orderflow.core.service.processing;

import com.codewalnut.orderflow.core.domain.inventory.Reservation;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.domain.pricing.DiscountResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservedOrderAttemptTest {

    private final List<ExecutorService> testExecutors = new ArrayList<>();

    @AfterEach
    void shutdownTestExecutors() throws InterruptedException {
        for (ExecutorService testExecutor : testExecutors) {
            testExecutor.shutdownNow();
            testExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void givenReservedOrderAttempt_whenCreated_thenBindsOrderReservationAndPricing() {
        // Arrange
        Order order = order("order-bound");
        Reservation reservation = new Reservation(order.getId(), Map.of("product-1", 1));
        DiscountResult pricing = pricing();

        // Act
        ReservedOrderAttempt attempt = new ReservedOrderAttempt(order, reservation, pricing);

        // Assert
        assertSame(order, attempt.order());
        assertSame(reservation, attempt.reservation());
        assertSame(pricing, attempt.pricing());
    }

    @Test
    void givenCompetingCallers_whenReservedAttemptIsSettled_thenOnlyOneCallerWins() throws Exception {
        // Arrange
        ReservedOrderAttempt attempt = new ReservedOrderAttempt(
                order("order-settlement"),
                new Reservation("order-settlement", Map.of("product-1", 1)),
                pricing());
        int callerCount = 8;
        CountDownLatch callersReady = new CountDownLatch(callerCount);
        CountDownLatch startSettlement = new CountDownLatch(1);
        ExecutorService callers = testExecutor(Executors.newFixedThreadPool(callerCount));
        List<Future<Boolean>> settlementResults = new ArrayList<>();
        for (int callerIndex = 0; callerIndex < callerCount; callerIndex++) {
            settlementResults.add(callers.submit(() -> {
                callersReady.countDown();
                startSettlement.await();
                return attempt.trySettle();
            }));
        }
        boolean callersWereReady = callersReady.await(2, TimeUnit.SECONDS);

        // Act
        startSettlement.countDown();
        long successfulCallers = 0;
        for (Future<Boolean> settlementResult : settlementResults) {
            if (settlementResult.get(2, TimeUnit.SECONDS)) {
                successfulCallers++;
            }
        }

        // Assert
        assertTrue(callersWereReady);
        assertEquals(1, successfulCallers);
    }

    @Test
    void givenReservationForAnotherOrder_whenReservedAttemptIsCreated_thenRejectsOwnershipMismatch() {
        // Arrange
        Order order = order("order-owner");
        Reservation reservation = new Reservation("different-order", Map.of("product-1", 1));
        DiscountResult pricing = pricing();

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ReservedOrderAttempt(order, reservation, pricing));

        // Assert
        assertTrue(exception.getMessage().contains(order.getId()));
        assertTrue(exception.getMessage().contains(reservation.orderId()));
    }

    private static Order order(String orderId) {
        OrderItem item = new OrderItem("product-1", "Product", new BigDecimal("10.00"), 1);
        return new Order(orderId, "customer-1", List.of(item), item.getLineTotal(), Instant.EPOCH);
    }

    private static DiscountResult pricing() {
        return new DiscountResult(
                List.of(),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("10.00"));
    }

    private ExecutorService testExecutor(ExecutorService testExecutor) {
        testExecutors.add(testExecutor);
        return testExecutor;
    }
}
