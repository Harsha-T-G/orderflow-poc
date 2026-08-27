package com.codewalnut.orderflow.core.service.processing;

import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.exception.PaymentFailedException;
import com.codewalnut.orderflow.core.service.payment.PaymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCoordinatorTest {

    private static final Duration TEST_WAIT = Duration.ofSeconds(2);
    private final List<PaymentCoordinator> coordinators = new ArrayList<>();
    private final List<ExecutorService> testExecutors = new ArrayList<>();

    @AfterEach
    void shutdownResources() throws InterruptedException {
        for (PaymentCoordinator coordinator : coordinators) {
            coordinator.shutdown();
        }
        for (ExecutorService testExecutor : testExecutors) {
            testExecutor.shutdownNow();
            testExecutor.awaitTermination(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void givenSuccessfulGateway_whenPaymentIsCharged_thenCompletesWithSuccess() throws Exception {
        // Arrange
        Order order = order("order-success");
        PaymentCoordinator coordinator = coordinator((ignoredOrder, ignoredAmount) -> {
        }, 1, 1, Duration.ofSeconds(1));

        // Act
        PaymentOutcome outcome = coordinator.charge(order, new BigDecimal("10.00"))
                .get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertEquals(PaymentOutcome.Kind.SUCCESS, outcome.kind());
        assertTrue(outcome.reason().contains(order.getId()));
        assertNull(outcome.cause());
    }

    @Test
    void givenDeclaredGatewayFailure_whenPaymentIsCharged_thenPreservesFailure() throws Exception {
        // Arrange
        Order order = order("order-declared-failure");
        PaymentFailedException gatewayFailure = new PaymentFailedException(order.getId());
        PaymentCoordinator coordinator = coordinator((ignoredOrder, ignoredAmount) -> {
            throw gatewayFailure;
        }, 1, 1, Duration.ofSeconds(1));

        // Act
        PaymentOutcome outcome = coordinator.charge(order, new BigDecimal("10.00"))
                .get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertEquals(PaymentOutcome.Kind.DECLARED_FAILURE, outcome.kind());
        assertTrue(outcome.reason().contains(order.getId()));
        assertSame(gatewayFailure, outcome.cause());
    }

    @Test
    void givenUnexpectedGatewayFailure_whenPaymentIsCharged_thenPreservesCause() throws Exception {
        // Arrange
        Order order = order("order-runtime-failure");
        IllegalStateException gatewayFailure = new IllegalStateException("adapter unavailable");
        PaymentCoordinator coordinator = coordinator((ignoredOrder, ignoredAmount) -> {
            throw gatewayFailure;
        }, 1, 1, Duration.ofSeconds(1));

        // Act
        PaymentOutcome outcome = coordinator.charge(order, new BigDecimal("10.00"))
                .get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertEquals(PaymentOutcome.Kind.UNEXPECTED_FAILURE, outcome.kind());
        assertTrue(outcome.reason().contains(order.getId()));
        assertTrue(outcome.reason().contains("adapter unavailable"));
        PaymentFailedException translated = assertInstanceOf(PaymentFailedException.class, outcome.cause());
        assertSame(gatewayFailure, translated.getCause());
    }

    @Test
    void givenBlockedGateway_whenDeadlineExpires_thenTimesOutAndInterruptsAdapter() throws Exception {
        // Arrange
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        PaymentGateway gateway = (ignoredOrder, ignoredAmount) -> {
            gatewayStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interruptionObserved.countDown();
                Thread.currentThread().interrupt();
            }
        };
        PaymentCoordinator coordinator = coordinator(gateway, 1, 1, Duration.ofMillis(50));

        // Act
        CompletableFuture<PaymentOutcome> payment = coordinator.charge(order("order-timeout"), new BigDecimal("10.00"));
        boolean gatewayWasStarted = gatewayStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        PaymentOutcome outcome = payment.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean wasInterrupted = interruptionObserved.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(gatewayWasStarted);
        assertEquals(PaymentOutcome.Kind.TIMED_OUT, outcome.kind());
        assertTrue(wasInterrupted);
    }

    @Test
    void givenFullPaymentQueue_whenAnotherPaymentIsCharged_thenRejectsWithoutBlocking() throws Exception {
        // Arrange
        CountDownLatch runningGatewayStarted = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        PaymentGateway gateway = (ignoredOrder, ignoredAmount) -> {
            runningGatewayStarted.countDown();
            awaitIgnoringInterruptUntilReleased(releaseGateway);
        };
        PaymentCoordinator coordinator = coordinator(gateway, 1, 1, Duration.ofSeconds(1));
        CompletableFuture<PaymentOutcome> running =
                coordinator.charge(order("order-running"), new BigDecimal("10.00"));
        boolean runningGatewayWasStarted =
                runningGatewayStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<PaymentOutcome> queued =
                coordinator.charge(order("order-queued"), new BigDecimal("10.00"));

        // Act
        PaymentOutcome rejected = coordinator.charge(order("order-rejected"), new BigDecimal("10.00"))
                .get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        releaseGateway.countDown();
        running.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        queued.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(runningGatewayWasStarted);
        assertEquals(PaymentOutcome.Kind.REJECTED, rejected.kind());
        assertTrue(rejected.reason().contains("order-rejected"));
    }

    @Test
    void givenRunningAndQueuedPayments_whenCoordinatorShutsDown_thenCancelsBothAndTerminatesExecutors()
            throws Exception {
        // Arrange
        CountDownLatch runningGatewayStarted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        PaymentGateway gateway = (ignoredOrder, ignoredAmount) -> {
            runningGatewayStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interruptionObserved.countDown();
                Thread.currentThread().interrupt();
            }
        };
        PaymentCoordinator coordinator = coordinator(gateway, 1, 1, Duration.ofSeconds(1));
        CompletableFuture<PaymentOutcome> running =
                coordinator.charge(order("order-running-shutdown"), new BigDecimal("10.00"));
        boolean runningGatewayWasStarted =
                runningGatewayStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<PaymentOutcome> queued =
                coordinator.charge(order("order-queued-shutdown"), new BigDecimal("10.00"));

        // Act
        coordinator.shutdown();
        PaymentOutcome runningOutcome = running.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        PaymentOutcome queuedOutcome = queued.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean runningGatewayWasInterrupted =
                interruptionObserved.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(runningGatewayWasStarted);
        assertEquals(PaymentOutcome.Kind.CANCELLED, runningOutcome.kind());
        assertEquals(PaymentOutcome.Kind.CANCELLED, queuedOutcome.kind());
        assertTrue(runningGatewayWasInterrupted);
        assertTrue(coordinator.isTerminated());
    }

    @Test
    void givenBarrierReleasedSuccessAndDeadline_whenAttemptSettles_thenPublishesExactlyOneStableOutcome()
            throws Exception {
        // Arrange
        CyclicBarrier settlementBarrier = new CyclicBarrier(2);
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        PaymentGateway gateway = (ignoredOrder, ignoredAmount) -> {
            gatewayStarted.countDown();
            try {
                settlementBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("Gateway settlement barrier failed", exception);
            }
        };
        PaymentCoordinator coordinator = coordinator(gateway, 1, 1, Duration.ofDays(1));
        List<PaymentOutcome> publishedOutcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
        ExecutorService deadlineTrigger = testExecutor(Executors.newSingleThreadExecutor());

        // Act
        CompletableFuture<PaymentOutcome> payment =
                coordinator.charge(order("order-race"), new BigDecimal("10.00"));
        payment.thenAccept(publishedOutcomes::add);
        boolean gatewayWasReady = gatewayStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        Runnable manualDeadline = coordinator.deadlineTrigger(payment);
        var deadlineResult = deadlineTrigger.submit(() -> {
            settlementBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            manualDeadline.run();
            return true;
        });
        PaymentOutcome outcome = payment.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean deadlineAttemptedSettlement = deadlineResult.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        PaymentOutcome.Kind stableKind = outcome.kind();
        String stableReason = outcome.reason();
        Throwable stableCause = outcome.cause();

        // Assert
        assertTrue(gatewayWasReady);
        assertTrue(deadlineAttemptedSettlement);
        assertTrue(outcome.kind() == PaymentOutcome.Kind.SUCCESS || outcome.kind() == PaymentOutcome.Kind.TIMED_OUT);
        assertEquals(1, publishedOutcomes.size());
        assertSame(outcome, publishedOutcomes.getFirst());
        assertEquals(stableKind, outcome.kind());
        assertEquals(stableReason, outcome.reason());
        assertSame(stableCause, outcome.cause());
        assertFalse(payment.isCompletedExceptionally());
    }

    @Test
    void givenDeadlineSchedulerRejects_whenPaymentIsCharged_thenRejectsAndCancelsTrackedTask() throws Exception {
        // Arrange
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        PaymentGateway gateway = (ignoredOrder, ignoredAmount) -> {
            gatewayStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interruptionObserved.countDown();
                Thread.currentThread().interrupt();
            }
        };
        RejectedExecutionException schedulerFailure = new RejectedExecutionException("deadline scheduler unavailable");
        ScheduledThreadPoolExecutor rejectedScheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                try {
                    if (!gatewayStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Gateway did not start before deadline scheduling");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while awaiting gateway start", exception);
                }
                throw schedulerFailure;
            }
        };
        PaymentCoordinator coordinator = coordinator(
                gateway,
                1,
                1,
                Duration.ofSeconds(1),
                rejectedScheduler);

        // Act
        PaymentOutcome outcome = coordinator.charge(order("order-scheduler-rejected"), new BigDecimal("10.00"))
                .get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean gatewayWasInterrupted = interruptionObserved.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        int trackedAttempts = coordinator.activeAttemptCount();

        // Assert
        assertEquals(PaymentOutcome.Kind.REJECTED, outcome.kind());
        assertTrue(outcome.reason().contains("order-scheduler-rejected"));
        assertTrue(outcome.reason().contains("deadline"));
        assertSame(schedulerFailure, outcome.cause());
        assertEquals(0, gatewayStarted.getCount());
        assertTrue(gatewayWasInterrupted);
        assertEquals(0, trackedAttempts);
    }

    @Test
    void givenFullPaymentQueue_whenPaymentIsRejected_thenRejectedAttemptIsNotTracked() throws Exception {
        // Arrange
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        PaymentGateway gateway = (ignoredOrder, ignoredAmount) -> {
            gatewayStarted.countDown();
            awaitIgnoringInterruptUntilReleased(releaseGateway);
        };
        PaymentCoordinator coordinator = coordinator(gateway, 1, 1, Duration.ofDays(1));
        CompletableFuture<PaymentOutcome> running =
                coordinator.charge(order("order-tracked-running"), new BigDecimal("10.00"));
        boolean runningWasStarted = gatewayStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<PaymentOutcome> queued =
                coordinator.charge(order("order-tracked-queued"), new BigDecimal("10.00"));

        // Act
        PaymentOutcome rejected = coordinator.charge(order("order-not-tracked"), new BigDecimal("10.00"))
                .get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        int attemptsAfterRejection = coordinator.activeAttemptCount();
        releaseGateway.countDown();
        running.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        queued.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        int attemptsAfterCompletion = coordinator.activeAttemptCount();

        // Assert
        assertTrue(runningWasStarted);
        assertEquals(PaymentOutcome.Kind.REJECTED, rejected.kind());
        assertEquals(2, attemptsAfterRejection);
        assertEquals(0, attemptsAfterCompletion);
    }

    @Test
    void givenChargeCompetesWithShutdown_whenLifecycleSettles_thenAttemptIsRejectedOrCancelledAndUntracked()
            throws Exception {
        // Arrange
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        PaymentGateway gateway = (ignoredOrder, ignoredAmount) -> {
            gatewayStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        PaymentCoordinator coordinator = coordinator(gateway, 1, 1, Duration.ofDays(1));
        CyclicBarrier lifecycleBarrier = new CyclicBarrier(2);
        ExecutorService contenders = testExecutor(Executors.newFixedThreadPool(2));

        // Act
        var chargeResult = contenders.submit(() -> {
            lifecycleBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return coordinator.charge(order("order-lifecycle-race"), new BigDecimal("10.00"));
        });
        var shutdownResult = contenders.submit(() -> {
            lifecycleBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            coordinator.shutdown();
            return true;
        });
        CompletableFuture<PaymentOutcome> payment = chargeResult.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        PaymentOutcome outcome = payment.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean shutdownCompleted = shutdownResult.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean gatewayMayHaveStarted = gatewayStarted.getCount() == 0;
        int trackedAttempts = coordinator.activeAttemptCount();

        // Assert
        assertTrue(shutdownCompleted);
        assertTrue(outcome.kind() == PaymentOutcome.Kind.REJECTED || outcome.kind() == PaymentOutcome.Kind.CANCELLED);
        if (gatewayMayHaveStarted) {
            assertEquals(PaymentOutcome.Kind.CANCELLED, outcome.kind());
        }
        assertEquals(0, trackedAttempts);
        assertTrue(coordinator.isTerminated());
    }

    private PaymentCoordinator coordinator(
            PaymentGateway gateway,
            int workerCount,
            int queueCapacity,
            Duration paymentDeadline) {
        PaymentCoordinator coordinator = new PaymentCoordinator(
                gateway,
                new OrderProcessingPolicy(
                        1,
                        1,
                        workerCount,
                        queueCapacity,
                        1,
                        1,
                        paymentDeadline,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)));
        coordinators.add(coordinator);
        return coordinator;
    }

    private ExecutorService testExecutor(ExecutorService testExecutor) {
        testExecutors.add(testExecutor);
        return testExecutor;
    }

    private PaymentCoordinator coordinator(
            PaymentGateway gateway,
            int workerCount,
            int queueCapacity,
            Duration paymentDeadline,
            ScheduledThreadPoolExecutor deadlineExecutor) {
        PaymentCoordinator coordinator = new PaymentCoordinator(
                gateway,
                new OrderProcessingPolicy(
                        1,
                        1,
                        workerCount,
                        queueCapacity,
                        1,
                        1,
                        paymentDeadline,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                deadlineExecutor);
        coordinators.add(coordinator);
        return coordinator;
    }

    private static Order order(String orderId) {
        OrderItem item = new OrderItem("product-1", "Product", new BigDecimal("10.00"), 1);
        return new Order(orderId, "customer-1", List.of(item), item.getLineTotal(), Instant.EPOCH);
    }

    private static void awaitIgnoringInterruptUntilReleased(CountDownLatch releaseGateway) {
        boolean wasInterrupted = false;
        while (true) {
            try {
                releaseGateway.await();
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
