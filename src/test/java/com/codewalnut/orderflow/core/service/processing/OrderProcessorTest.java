package com.codewalnut.orderflow.core.service.processing;

import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.domain.pricing.DiscountRule;
import com.codewalnut.orderflow.core.exception.DuplicateOrderSubmissionException;
import com.codewalnut.orderflow.core.exception.InvalidOrderStatusTransitionException;
import com.codewalnut.orderflow.core.exception.OrderQueueCapacityException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.notification.NotificationChannel;
import com.codewalnut.orderflow.core.service.order.OrderFactory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import com.codewalnut.orderflow.core.service.payment.AlwaysSuccessfulPaymentGateway;
import com.codewalnut.orderflow.core.service.payment.ConfigurableFailurePaymentGateway;
import com.codewalnut.orderflow.core.service.payment.PaymentGateway;
import com.codewalnut.orderflow.core.service.pricing.DiscountEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderProcessorTest {

    private OrderProcessor processor;

    @AfterEach
    void shutDownProcessor() {
        if (processor != null) {
            processor.shutdown();
        }
    }

    @Test
    void givenCreatedOrder_whenSubmittedAndProcessed_thenCompletesWithPricingAndReservedStock() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        Order order = fixture.createOrder("ORD-1", "C-PREM", "P-1", 2);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(new BigDecimal("1.00"), order.getDiscountAmount().orElseThrow());
        assertEquals(new BigDecimal("19.00"), order.getFinalAmount().orElseThrow());
        assertEquals(8, fixture.inventory.availableQuantity("P-1"));
        assertTrue(fixture.audit.eventsFor("ORD-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.CREATED));
        assertTrue(fixture.audit.eventsFor("ORD-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.COMPLETED));
    }

    @Test
    void givenDuplicateOrderId_whenSubmitted_thenThrowsAndProcessesAtMostOnce() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        Order first = fixture.createOrder("DUP-1", "C-PREM", "P-1", 1);
        Order second = fixture.createOrder("DUP-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(first);
        DuplicateOrderSubmissionException exception = assertThrows(
                DuplicateOrderSubmissionException.class,
                () -> processor.submit(second));
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertTrue(exception.getMessage().contains("DUP-1"));
        assertEquals(OrderStatus.COMPLETED, first.getStatus());
        assertEquals(OrderStatus.CREATED, second.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenCancelledQueuedOrder_whenWorkerDequeues_thenSkippedWithoutStockChange() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(), false);
        Order order = fixture.createOrder("CAN-1", "C-PREM", "P-1", 2);
        processor.submit(order);
        order.cancel();
        processor.start();

        // Act
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
        assertTrue(fixture.audit.eventsFor("CAN-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.SKIPPED));
    }

    @Test
    void givenCancelledCreatedOrder_whenSubmitted_thenThrowsAndSameIdCanBeSubmittedLater() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        Order cancelled = fixture.createOrder("REUSE-1", "C-PREM", "P-1", 1);
        cancelled.cancel();

        // Act
        assertThrows(InvalidOrderStatusTransitionException.class, () -> processor.submit(cancelled));
        Order replacement = fixture.createOrder("REUSE-1", "C-PREM", "P-1", 1);
        processor.submit(replacement);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        assertEquals(OrderStatus.COMPLETED, replacement.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenPaymentFailure_whenProcessed_thenReservationIsReleasedAndOrderFails() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new ConfigurableFailurePaymentGateway(Set.of("PAY-1")), List.of());
        Order order = fixture.createOrder("PAY-1", "C-PREM", "P-1", 3);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertTrue(order.getFailureReason().orElseThrow().toLowerCase().contains("payment"));
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
        assertTrue(fixture.audit.eventsFor("PAY-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.RELEASE));
        assertTrue(fixture.audit.eventsFor("PAY-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.FAILED));
    }

    @Test
    void givenFailingNotificationChannel_whenOrderCompletes_thenFinalStateDoesNotChange() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        List<String> successful = new CopyOnWriteArrayList<>();
        NotificationChannel failing = order -> {
            throw new IllegalStateException("email down");
        };
        NotificationChannel succeeding = order -> successful.add(order.getId());
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(failing, succeeding));
        Order order = fixture.createOrder("NOTE-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(List.of("NOTE-1"), successful);
        assertTrue(fixture.audit.eventsFor("NOTE-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.NOTIFICATION
                        && event.message().toLowerCase().contains("email down")));
    }

    @RepeatedTest(3)
    void givenLimitedStock_whenManyOrdersAreSubmittedConcurrently_thenSoldQuantityNeverExceedsStock() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        int orderCount = 20;
        List<Order> orders = new ArrayList<>();
        for (int orderIndex = 0; orderIndex < orderCount; orderIndex++) {
            orders.add(fixture.createOrder("CONT-" + orderIndex, "C-PREM", "P-1", 1));
        }
        CyclicBarrier start = new CyclicBarrier(orderCount);
        CountDownLatch submitted = new CountDownLatch(orderCount);

        // Act
        for (Order order : orders) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    processor.submit(order);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                } finally {
                    submitted.countDown();
                }
            });
            thread.start();
        }
        assertTrue(submitted.await(5, TimeUnit.SECONDS));
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        long completed = orders.stream().filter(order -> order.getStatus() == OrderStatus.COMPLETED).count();
        long failed = orders.stream().filter(order -> order.getStatus() == OrderStatus.FAILED).count();
        assertEquals(orderCount, completed + failed);
        assertEquals(10, completed);
        assertEquals(0, fixture.inventory.availableQuantity("P-1"));
        assertTrue(orders.stream().noneMatch(order -> order.getStatus() == OrderStatus.PROCESSING));
        assertTrue(orders.stream().noneMatch(order -> order.getStatus() == OrderStatus.QUEUED));
    }

    @Test
    void givenWorkerFailure_whenOneOrderThrows_thenOtherOrdersStillComplete() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        AtomicInteger charges = new AtomicInteger();
        PaymentGateway explodingThenSucceeding = (order, amount) -> {
            if (order.getId().equals("BOOM-1")) {
                throw new IllegalStateException("gateway panic");
            }
            charges.incrementAndGet();
        };
        processor = fixture.processor(explodingThenSucceeding, List.of());
        Order exploding = fixture.createOrder("BOOM-1", "C-PREM", "P-1", 1);
        Order surviving = fixture.createOrder("OK-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(exploding);
        processor.submit(surviving);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.FAILED, exploding.getStatus());
        assertEquals(OrderStatus.COMPLETED, surviving.getStatus());
        assertEquals(1, charges.get());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenAcceptedWork_whenShutdownRequested_thenExecutorsTerminate() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        CountDownLatch paymentStarted = new CountDownLatch(1);
        CountDownLatch allowPayment = new CountDownLatch(1);
        PaymentGateway gated = (order, amount) -> {
            paymentStarted.countDown();
            try {
                assertTrue(allowPayment.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to complete payment", exception);
            }
        };
        processor = fixture.processor(gated, List.of());
        Order order = fixture.createOrder("STOP-1", "C-PREM", "P-1", 1);
        processor.submit(order);
        assertTrue(paymentStarted.await(5, TimeUnit.SECONDS));

        // Act
        allowPayment.countDown();
        processor.shutdown();

        // Assert
        assertTrue(processor.isShutdown());
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertThrows(IllegalStateException.class, () -> processor.submit(
                fixture.createOrder("STOP-2", "C-PREM", "P-1", 1)));
    }

    @Test
    void givenFullIngressQueue_whenAnotherOrderIsSubmitted_thenRejectsBeforeMutationAndSameIdCanBeRetried()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        OrderProcessingPolicy policy = fixture.policy(1, 1, 1, 1);
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(), fixture.audit, policy, false);
        Order occupying = fixture.createOrder("CAP-0", "C-PREM", "P-1", 1);
        Order rejected = fixture.createOrder("CAP-1", "C-PREM", "P-1", 1);
        processor.submit(occupying);

        // Act
        OrderQueueCapacityException exception = assertThrows(
                OrderQueueCapacityException.class,
                () -> processor.submit(rejected));

        // Assert
        assertTrue(exception.getMessage().contains("CAP-1"));
        assertEquals(OrderStatus.CREATED, rejected.getStatus());
        assertEquals(List.of(occupying), processor.snapshotOrders());
        assertTrue(fixture.audit.eventsFor("CAP-1").stream()
                .noneMatch(event -> event.type() == AuditEventType.QUEUED));

        // Act
        processor.start();
        processor.awaitIdle(Duration.ofSeconds(5));
        processor.submit(rejected);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, rejected.getStatus());
        assertTrue(processor.snapshotOrders().containsAll(List.of(occupying, rejected)));
        assertEquals(8, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenQueuedAuditThrows_whenSubmitted_thenOrderIsRolledBackAndSameIdCanBeRetried() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(
                new AlwaysSuccessfulPaymentGateway(),
                List.of(),
                new ThrowingOnTypeAuditLog(AuditEventType.QUEUED));
        Order interrupted = fixture.createOrder("AUD-Q-1", "C-PREM", "P-1", 1);

        // Act
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> processor.submit(interrupted));

        // Assert
        assertTrue(exception.getMessage().toLowerCase().contains("audit")
                || exception.getCause() instanceof IllegalStateException);
        assertEquals(OrderStatus.CREATED, interrupted.getStatus());
        assertTrue(processor.snapshotOrders().isEmpty());

        // Arrange
        processor.shutdown();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        Order replacement = fixture.createOrder("AUD-Q-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(replacement);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, replacement.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenReservationSucceeded_whenPostReservationWorkThrows_thenReservationIsReleasedAndOrderFails()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(
                new AlwaysSuccessfulPaymentGateway(),
                List.of(),
                new ThrowingOnTypeAuditLog(AuditEventType.RESERVATION));
        Order order = fixture.createOrder("RES-LEAK-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertTrue(order.getFailureReason().orElseThrow().toLowerCase().contains("audit"));
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenPaymentFailed_whenReleaseAuditThrows_thenOrderStillFailsAndIdleCompletes() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(
                new ConfigurableFailurePaymentGateway(Set.of("PAY-AUD-1")),
                List.of(),
                new ThrowingOnTypeAuditLog(AuditEventType.RELEASE));
        Order order = fixture.createOrder("PAY-AUD-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertTrue(order.getFailureReason().orElseThrow().toLowerCase().contains("payment"));
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenPaymentSucceeded_whenPostCompletionWorkThrows_thenStockStaysConsumedAndOrderRemainsCompleted()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(
                new AlwaysSuccessfulPaymentGateway(),
                List.of(),
                new ThrowingOnTypeAuditLog(AuditEventType.PAYMENT));
        Order order = fixture.createOrder("PAY-AUDIT-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenCompletedAuditThrows_whenPaymentSucceeded_thenStockStaysConsumedAndOrderRemainsCompleted()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(
                new AlwaysSuccessfulPaymentGateway(),
                List.of(),
                new ThrowingOnTypeAuditLog(AuditEventType.COMPLETED));
        Order order = fixture.createOrder("COMP-AUDIT-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenThreeBlockedPayments_whenTheyTimeOut_thenLaterOrderCompletesWithoutPermanentStarvation()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        CountDownLatch blockedPaymentsStarted = new CountDownLatch(3);
        AtomicInteger chargeSequence = new AtomicInteger();
        PaymentGateway gateway = (order, amount) -> {
            if (chargeSequence.incrementAndGet() <= 3) {
                blockedPaymentsStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        OrderProcessingPolicy policy = fixture.policy(
                3, 8, 3, 3, Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(2));
        processor = fixture.processor(gateway, List.of(), fixture.audit, policy, true);
        List<Order> blockedOrders = List.of(
                fixture.createOrder("TIME-1", "C-PREM", "P-1", 1),
                fixture.createOrder("TIME-2", "C-PREM", "P-1", 1),
                fixture.createOrder("TIME-3", "C-PREM", "P-1", 1));
        blockedOrders.forEach(processor::submit);
        assertTrue(blockedPaymentsStarted.await(2, TimeUnit.SECONDS));
        Order laterOrder = fixture.createOrder("TIME-4", "C-PREM", "P-1", 1);

        // Act
        processor.submit(laterOrder);
        processor.awaitIdle(Duration.ofSeconds(3));

        // Assert
        assertTrue(blockedOrders.stream().allMatch(order -> order.getStatus() == OrderStatus.FAILED));
        assertEquals(OrderStatus.COMPLETED, laterOrder.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenBoundedPaymentStageIsFull_whenAnotherReservationIsHandedOff_thenExactStockIsReleased()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        CountDownLatch releaseGateway = new CountDownLatch(1);
        CountDownLatch runningPaymentStarted = new CountDownLatch(1);
        CountDownLatch threeReservationsRecorded = new CountDownLatch(3);
        CountDownLatch capacityRejectedOrderFailed = new CountDownLatch(1);
        AuditLog countingAudit = new PaymentCapacityAuditLog(
                threeReservationsRecorded,
                Set.of("PAY-CAP-2", "PAY-CAP-3"),
                capacityRejectedOrderFailed);
        PaymentGateway gateway = (order, amount) -> {
            runningPaymentStarted.countDown();
            awaitUninterruptibly(releaseGateway);
        };
        OrderProcessingPolicy policy = fixture.policy(
                3, 8, 1, 1, Duration.ofDays(1), Duration.ofSeconds(1), Duration.ofSeconds(2));
        processor = fixture.processor(gateway, List.of(), countingAudit, policy, true);
        Order running = fixture.createOrder("PAY-CAP-1", "C-PREM", "P-1", 1);
        Order queued = fixture.createOrder("PAY-CAP-2", "C-PREM", "P-1", 1);
        Order rejected = fixture.createOrder("PAY-CAP-3", "C-PREM", "P-1", 1);

        // Act
        processor.submit(running);
        assertTrue(runningPaymentStarted.await(2, TimeUnit.SECONDS));
        processor.submit(queued);
        processor.submit(rejected);
        assertTrue(threeReservationsRecorded.await(2, TimeUnit.SECONDS));
        assertTrue(capacityRejectedOrderFailed.await(2, TimeUnit.SECONDS));

        // Assert
        assertEquals(
                1,
                List.of(queued, rejected).stream()
                        .filter(order -> order.getStatus() == OrderStatus.FAILED)
                        .count());
        assertEquals(8, fixture.inventory.availableQuantity("P-1"));

        // Act
        releaseGateway.countDown();
        processor.awaitIdle(Duration.ofSeconds(3));

        // Assert
        assertEquals(OrderStatus.COMPLETED, running.getStatus());
        assertEquals(
                1,
                List.of(queued, rejected).stream()
                        .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                        .count());
        assertEquals(8, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenQueuedAndRunningPayments_whenShutdownCancelsThem_thenEveryOrderIsFinalAndStockIsRestored()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        CountDownLatch runningPaymentStarted = new CountDownLatch(1);
        CountDownLatch twoReservationsRecorded = new CountDownLatch(2);
        AuditLog countingAudit = new CountingTypeAuditLog(AuditEventType.RESERVATION, twoReservationsRecorded);
        PaymentGateway gateway = (order, amount) -> {
            runningPaymentStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        OrderProcessingPolicy policy = fixture.policy(
                2, 4, 1, 1, Duration.ofDays(1), Duration.ofSeconds(1), Duration.ofSeconds(2));
        processor = fixture.processor(gateway, List.of(), countingAudit, policy, true);
        Order running = fixture.createOrder("SHUT-PAY-1", "C-PREM", "P-1", 1);
        Order queued = fixture.createOrder("SHUT-PAY-2", "C-PREM", "P-1", 1);
        processor.submit(running);
        processor.submit(queued);
        assertTrue(runningPaymentStarted.await(2, TimeUnit.SECONDS));
        assertTrue(twoReservationsRecorded.await(2, TimeUnit.SECONDS));

        // Act
        processor.shutdown();
        processor.awaitIdle(Duration.ofSeconds(1));

        // Assert
        assertEquals(OrderStatus.FAILED, running.getStatus());
        assertEquals(OrderStatus.FAILED, queued.getStatus());
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
        assertTrue(processor.isShutdown());
    }

    @Test
    void givenCompletedOrderNotificationIsBlocked_whenShutdownCancelsDelivery_thenFinalStateDoesNotChange()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        CountDownLatch notificationStarted = new CountDownLatch(1);
        NotificationChannel blockedChannel = order -> {
            notificationStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(blockedChannel));
        Order order = fixture.createOrder("SHUT-NOTE-1", "C-PREM", "P-1", 1);
        processor.submit(order);
        assertTrue(notificationStarted.await(2, TimeUnit.SECONDS));

        // Act
        processor.shutdown();
        processor.awaitIdle(Duration.ofSeconds(1));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
        assertTrue(processor.isShutdown());
    }

    @Test
    void givenFailedOrderNotificationIsBlocked_whenShutdownCancelsDelivery_thenFinalStateDoesNotChange()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        CountDownLatch notificationStarted = new CountDownLatch(1);
        NotificationChannel blockedChannel = order -> {
            notificationStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        processor = fixture.processor(
                new ConfigurableFailurePaymentGateway(Set.of("SHUT-NOTE-FAIL-1")),
                List.of(blockedChannel));
        Order order = fixture.createOrder("SHUT-NOTE-FAIL-1", "C-PREM", "P-1", 1);
        processor.submit(order);
        assertTrue(notificationStarted.await(2, TimeUnit.SECONDS));

        // Act
        processor.shutdown();
        processor.awaitIdle(Duration.ofSeconds(1));

        // Assert
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
        assertTrue(processor.isShutdown());
    }

    @Test
    void givenSubmitOverlapsShutdown_whenLifecycleLockChoosesWinner_thenSubmissionIsRejectedOrEndsFinal()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        Order order = fixture.createOrder("LIFE-RACE-1", "C-PREM", "P-1", 1);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicBoolean accepted = new AtomicBoolean();
        ExecutorService contenders = Executors.newFixedThreadPool(2);

        // Act
        var submission = contenders.submit(() -> {
            start.await(2, TimeUnit.SECONDS);
            try {
                processor.submit(order);
                accepted.set(true);
            } catch (IllegalStateException expectedShutdownRejection) {
                assertTrue(expectedShutdownRejection.getMessage().contains(order.getId()));
            }
            return true;
        });
        var shutdown = contenders.submit(() -> {
            start.await(2, TimeUnit.SECONDS);
            processor.shutdown();
            return true;
        });
        assertTrue(submission.get(3, TimeUnit.SECONDS));
        assertTrue(shutdown.get(3, TimeUnit.SECONDS));
        contenders.shutdownNow();

        // Assert
        if (accepted.get()) {
            assertTrue(order.getStatus() == OrderStatus.COMPLETED
                    || order.getStatus() == OrderStatus.FAILED
                    || order.getStatus() == OrderStatus.CANCELLED);
        } else {
            assertEquals(OrderStatus.CREATED, order.getStatus());
        }
        assertTrue(processor.isShutdown());
    }

    @Test
    void givenQueuedOrdersWhenWorkersHaveNotStarted_whenShutdownRuns_thenOrdersAreCancelledAndIdleCloses()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(), false);
        Order queued = fixture.createOrder("SHUT-QUEUE-1", "C-PREM", "P-1", 1);
        processor.submit(queued);

        // Act
        processor.shutdown();
        processor.awaitIdle(Duration.ofSeconds(1));

        // Assert
        assertEquals(OrderStatus.CANCELLED, queued.getStatus());
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
        assertTrue(processor.isShutdown());
    }

    @Test
    void givenCallerIsInterrupted_whenShutdownRuns_thenAllPhasesStartAndInterruptStatusIsPreserved()
            throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(), false);
        Order queued = fixture.createOrder("INT-SHUT-1", "C-PREM", "P-1", 1);
        processor.submit(queued);

        // Act
        Thread.currentThread().interrupt();
        processor.shutdown();
        boolean interruptWasPreserved = Thread.currentThread().isInterrupted();
        Thread.interrupted();

        // Assert
        assertTrue(interruptWasPreserved);
        assertEquals(OrderStatus.CANCELLED, queued.getStatus());
        assertTrue(processor.isShutdown());
    }

    private static final class ThrowingOnTypeAuditLog extends AuditLog {
        private final AuditEventType explodingType;

        private ThrowingOnTypeAuditLog(AuditEventType explodingType) {
            this.explodingType = explodingType;
        }

        @Override
        public void record(String orderId, AuditEventType type, String message) {
            if (type == explodingType) {
                throw new IllegalStateException("audit failed on " + type);
            }
            super.record(orderId, type, message);
        }
    }

    private static final class CountingTypeAuditLog extends AuditLog {
        private final AuditEventType countedType;
        private final CountDownLatch occurrences;

        private CountingTypeAuditLog(AuditEventType countedType, CountDownLatch occurrences) {
            this.countedType = countedType;
            this.occurrences = occurrences;
        }

        @Override
        public void record(String orderId, AuditEventType type, String message) {
            super.record(orderId, type, message);
            if (type == countedType) {
                occurrences.countDown();
            }
        }
    }

    private static final class PaymentCapacityAuditLog extends AuditLog {
        private final CountDownLatch reservations;
        private final Set<String> contendingOrderIds;
        private final CountDownLatch capacityRejectedOrderFailed;

        private PaymentCapacityAuditLog(
                CountDownLatch reservations,
                Set<String> contendingOrderIds,
                CountDownLatch capacityRejectedOrderFailed) {
            this.reservations = reservations;
            this.contendingOrderIds = Set.copyOf(contendingOrderIds);
            this.capacityRejectedOrderFailed = capacityRejectedOrderFailed;
        }

        @Override
        public void record(String orderId, AuditEventType type, String message) {
            super.record(orderId, type, message);
            if (type == AuditEventType.RESERVATION) {
                reservations.countDown();
            }
            if (type == AuditEventType.FAILED && contendingOrderIds.contains(orderId)) {
                capacityRejectedOrderFailed.countDown();
            }
        }
    }

    private static final class Fixture {
        private final Inventory inventory = new Inventory();
        private final ProductCatalog catalog = new ProductCatalog(inventory);
        private final CustomerDirectory customers = new CustomerDirectory();
        private final AuditLog audit = new AuditLog();
        private final OrderFactory factory;
        private final DiscountEngine discounts = new DiscountEngine(List.of(
                DiscountRule.regularCustomer(),
                DiscountRule.premiumCustomer(),
                DiscountRule.corporateCustomer(),
                DiscountRule.bulkQuantity(),
                DiscountRule.highValue()));
        private final OrderValidationPipeline pipeline = new OrderValidationPipeline(List.of(
                OrderValidationRule.nonEmptyRequest(),
                OrderValidationRule.positiveQuantities(),
                OrderValidationRule.customerExists(),
                OrderValidationRule.productExists(),
                OrderValidationRule.activeProducts(),
                OrderValidationRule.availableStock()));

        private Fixture() {
            customers.register(new Customer("C-PREM", "Pat Premium", "pat@example.com", CustomerType.PREMIUM));
            catalog.add(new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2), 10);
            factory = new OrderFactory(customers, catalog, inventory, pipeline, audit);
        }

        static Fixture premiumCatalog() {
            return new Fixture();
        }

        Order createOrder(String orderId, String customerId, String productId, int quantity) {
            return factory.create(orderId, new OrderRequest(customerId, List.of(new RequestedProduct(productId, quantity))));
        }

        OrderProcessor processor(
                PaymentGateway paymentGateway,
                List<NotificationChannel> channels) {
            return processor(paymentGateway, channels, true);
        }

        OrderProcessor processor(
                PaymentGateway paymentGateway,
                List<NotificationChannel> channels,
                boolean startWorkers) {
            return processor(paymentGateway, channels, audit, startWorkers);
        }

        OrderProcessor processor(
                PaymentGateway paymentGateway,
                List<NotificationChannel> channels,
                AuditLog auditLog) {
            return processor(paymentGateway, channels, auditLog, true);
        }

        private OrderProcessor processor(
                PaymentGateway paymentGateway,
                List<NotificationChannel> channels,
                AuditLog auditLog,
                boolean startWorkers) {
            return processor(paymentGateway, channels, auditLog, OrderProcessingPolicy.defaults(), startWorkers);
        }

        private OrderProcessor processor(
                PaymentGateway paymentGateway,
                List<NotificationChannel> channels,
                AuditLog auditLog,
                OrderProcessingPolicy policy,
                boolean startWorkers) {
            return new OrderProcessor(
                    catalog,
                    customers,
                    inventory,
                    pipeline,
                    discounts,
                    paymentGateway,
                    channels,
                    auditLog,
                    policy,
                    startWorkers);
        }

        private OrderProcessingPolicy policy(
                int orderWorkers,
                int orderCapacity,
                int paymentWorkers,
                int paymentCapacity) {
            return policy(
                    orderWorkers,
                    orderCapacity,
                    paymentWorkers,
                    paymentCapacity,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2));
        }

        private OrderProcessingPolicy policy(
                int orderWorkers,
                int orderCapacity,
                int paymentWorkers,
                int paymentCapacity,
                Duration paymentDeadline,
                Duration notificationDeadline,
                Duration shutdownBudget) {
            return new OrderProcessingPolicy(
                    orderWorkers,
                    orderCapacity,
                    paymentWorkers,
                    paymentCapacity,
                    1,
                    1,
                    paymentDeadline,
                    notificationDeadline,
                    shutdownBudget);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean wasInterrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                wasInterrupted = true;
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
