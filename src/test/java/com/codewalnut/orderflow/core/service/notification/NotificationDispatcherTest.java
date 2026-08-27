package com.codewalnut.orderflow.core.service.notification;

import com.codewalnut.orderflow.core.domain.audit.AuditEvent;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.processing.OrderProcessingPolicy;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationDispatcherTest {

    private static final Duration TEST_WAIT = Duration.ofSeconds(2);
    private final List<NotificationDispatcher> dispatchers = new ArrayList<>();
    private final List<ExecutorService> testExecutors = new ArrayList<>();
    private final List<Handler> notificationLogHandlers = new ArrayList<>();

    @AfterEach
    void shutdownResources() throws InterruptedException {
        Logger notificationLogger = Logger.getLogger(NotificationDispatcher.class.getName());
        for (Handler handler : notificationLogHandlers) {
            notificationLogger.removeHandler(handler);
        }
        for (NotificationDispatcher dispatcher : dispatchers) {
            dispatcher.shutdown();
        }
        for (ExecutorService testExecutor : testExecutors) {
            testExecutor.shutdownNow();
            testExecutor.awaitTermination(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void givenSuccessfulAndFailingChannels_whenDispatched_thenAllSettleAndOrderRemainsUnchanged() throws Exception {
        // Arrange
        AtomicInteger successfulDeliveries = new AtomicInteger();
        IllegalStateException channelFailure = new IllegalStateException("smtp unavailable");
        NotificationChannel successful = ignoredOrder -> successfulDeliveries.incrementAndGet();
        NotificationChannel failing = ignoredOrder -> {
            throw channelFailure;
        };
        AuditLog auditLog = new AuditLog();
        Order order = order("notification-mixed");
        NotificationDispatcher dispatcher = dispatcher(List.of(successful, failing), auditLog, 2, 2);

        // Act
        dispatcher.dispatch(order).get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        List<AuditEvent> outcomes = auditLog.eventsFor(order.getId());

        // Assert
        assertEquals(1, successfulDeliveries.get());
        assertEquals(2, outcomes.size());
        assertTrue(outcomes.stream().anyMatch(event -> event.message().contains("succeeded")));
        assertTrue(outcomes.stream().anyMatch(event -> event.message().contains("smtp unavailable")));
        assertEquals(OrderStatus.CREATED, order.getStatus());
    }

    @Test
    void givenBlockedChannel_whenDeadlineExpires_thenInterruptsChannelAndCompletesDispatch() throws Exception {
        // Arrange
        CountDownLatch channelStarted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        NotificationChannel blocked = ignoredOrder -> {
            channelStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interruptionObserved.countDown();
                Thread.currentThread().interrupt();
            }
        };
        AuditLog auditLog = new AuditLog();
        Order order = order("notification-timeout");
        NotificationDispatcher dispatcher =
                dispatcher(List.of(blocked), auditLog, 1, 1, Duration.ofDays(1));

        // Act
        CompletableFuture<Void> dispatch = dispatcher.dispatch(order);
        boolean wasStarted = channelStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        dispatcher.deadlineTrigger(dispatch, blocked).run();
        dispatch.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean wasInterrupted = interruptionObserved.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(wasStarted);
        assertTrue(wasInterrupted);
        assertTrue(auditLog.eventsFor(order.getId()).getFirst().message().contains("timed out"));
        assertEquals(0, dispatcher.activeAttemptCount());
    }

    @Test
    void givenFullNotificationQueue_whenAnotherDispatchIsSubmitted_thenCapacityRejectionIsAudited() throws Exception {
        // Arrange
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseChannel = new CountDownLatch(1);
        NotificationChannel blocked = ignoredOrder -> {
            runningStarted.countDown();
            awaitIgnoringInterruptUntilReleased(releaseChannel);
        };
        AuditLog auditLog = new AuditLog();
        NotificationDispatcher dispatcher =
                dispatcher(List.of(blocked), auditLog, 1, 1, Duration.ofDays(1));
        List<LogRecord> logRecords = captureNotificationLogs();
        CompletableFuture<Void> running = dispatcher.dispatch(order("notification-running"));
        boolean runningWasStarted = runningStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Void> queued = dispatcher.dispatch(order("notification-queued"));
        Order rejectedOrder = order("notification-rejected");

        // Act
        dispatcher.dispatch(rejectedOrder).get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        releaseChannel.countDown();
        running.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        queued.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(runningWasStarted);
        String rejectionMessage = auditLog.eventsFor(rejectedOrder.getId()).getFirst().message();
        assertTrue(rejectionMessage.contains("capacity"));
        assertTrue(rejectionMessage.contains(rejectedOrder.getId()));
        LogRecord capacityRejection = logRecords.stream()
                .filter(record -> record.getMessage().contains(rejectedOrder.getId()))
                .findFirst()
                .orElseThrow();
        assertInstanceOf(RejectedExecutionException.class, capacityRejection.getThrown());
    }

    @Test
    void givenDeadlineSchedulerRejects_whenDispatched_thenSchedulerRejectionIsAuditedAndTaskIsInterrupted()
            throws Exception {
        // Arrange
        CountDownLatch channelStarted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        NotificationChannel blocked = ignoredOrder -> {
            channelStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interruptionObserved.countDown();
                Thread.currentThread().interrupt();
            }
        };
        RejectedExecutionException schedulerFailure =
                new RejectedExecutionException("deadline scheduler unavailable");
        ScheduledThreadPoolExecutor rejectedScheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                try {
                    if (!channelStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Channel did not start before deadline scheduling");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while awaiting channel start", exception);
                }
                throw schedulerFailure;
            }
        };
        AuditLog auditLog = new AuditLog();
        Order order = order("notification-scheduler-rejected");
        List<LogRecord> logRecords = captureNotificationLogs();
        NotificationDispatcher dispatcher =
                dispatcher(List.of(blocked), auditLog, 1, 1, Duration.ofSeconds(1), rejectedScheduler);

        // Act
        dispatcher.dispatch(order).get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean wasInterrupted = interruptionObserved.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(wasInterrupted);
        String rejectionMessage = auditLog.eventsFor(order.getId()).getFirst().message();
        assertTrue(rejectionMessage.contains("deadline scheduling rejected"));
        assertFalse(rejectionMessage.contains("shutdown"));
        LogRecord schedulerRejection = logRecords.stream()
                .filter(record -> record.getMessage().contains("deadline scheduling rejected"))
                .findFirst()
                .orElseThrow();
        assertSame(schedulerFailure, schedulerRejection.getThrown());
        assertEquals(0, dispatcher.activeAttemptCount());
    }

    @Test
    void givenRunningAndQueuedNotifications_whenShutdownRuns_thenBothAreCancelledAndExecutorsTerminate()
            throws Exception {
        // Arrange
        CountDownLatch channelStarted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        NotificationChannel blocked = ignoredOrder -> {
            channelStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interruptionObserved.countDown();
                Thread.currentThread().interrupt();
            }
        };
        AuditLog auditLog = new AuditLog();
        NotificationDispatcher dispatcher =
                dispatcher(List.of(blocked), auditLog, 1, 1, Duration.ofDays(1));
        Order runningOrder = order("notification-running-shutdown");
        Order queuedOrder = order("notification-queued-shutdown");
        CompletableFuture<Void> running = dispatcher.dispatch(runningOrder);
        boolean runningWasStarted = channelStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Void> queued = dispatcher.dispatch(queuedOrder);

        // Act
        dispatcher.shutdown(TEST_WAIT);
        running.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        queued.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean wasInterrupted = interruptionObserved.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(runningWasStarted);
        assertTrue(wasInterrupted);
        assertTrue(auditLog.eventsFor(runningOrder.getId()).getFirst().message().contains("shutdown"));
        assertTrue(auditLog.eventsFor(queuedOrder.getId()).getFirst().message().contains("shutdown"));
        assertEquals(0, dispatcher.activeAttemptCount());
        assertTrue(dispatcher.isTerminated());
    }

    @Test
    void givenAuditLogFailure_whenChannelSettles_thenAggregateStillCompletes() throws Exception {
        // Arrange
        AuditLog failingAuditLog = new AuditLog() {
            @Override
            public void record(
                    String orderId,
                    com.codewalnut.orderflow.core.domain.audit.AuditEventType type,
                    String message) {
                throw new IllegalStateException("audit unavailable");
            }
        };
        NotificationDispatcher dispatcher =
                dispatcher(List.of(ignoredOrder -> { }), failingAuditLog, 1, 1);

        // Act
        CompletableFuture<Void> dispatch = dispatcher.dispatch(order("notification-audit-failure"));
        dispatch.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(dispatch.isDone());
        assertFalse(dispatch.isCompletedExceptionally());
        assertEquals(0, dispatcher.activeAttemptCount());
    }

    @Test
    void givenJulHandlerFailure_whenChannelSettles_thenAuditAndAggregateStillComplete() throws Exception {
        // Arrange
        Handler throwingHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("logging unavailable");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        installNotificationLogHandler(throwingHandler);
        AuditLog auditLog = new AuditLog();
        Order order = order("notification-log-failure");
        NotificationDispatcher dispatcher =
                dispatcher(List.of(ignoredOrder -> { }), auditLog, 1, 1);

        // Act
        CompletableFuture<Void> dispatch = dispatcher.dispatch(order);
        dispatch.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertTrue(dispatch.isDone());
        assertFalse(dispatch.isCompletedExceptionally());
        assertEquals(1, auditLog.eventsFor(order.getId()).size());
        assertEquals(0, dispatcher.activeAttemptCount());
    }

    @Test
    void givenNoChannels_whenDispatched_thenCompletesImmediately() {
        // Arrange
        NotificationDispatcher dispatcher = dispatcher(List.of(), new AuditLog(), 1, 1);

        // Act
        CompletableFuture<Void> dispatch = dispatcher.dispatch(order("notification-empty"));

        // Assert
        assertTrue(dispatch.isDone());
        assertFalse(dispatch.isCompletedExceptionally());
    }

    @Test
    void givenMutableSourceList_whenDispatcherIsConstructed_thenLaterListChangesDoNotAlterOwnedChannels()
            throws Exception {
        // Arrange
        AtomicInteger deliveries = new AtomicInteger();
        List<NotificationChannel> channels = new ArrayList<>();
        channels.add(ignoredOrder -> deliveries.incrementAndGet());
        NotificationDispatcher dispatcher = dispatcher(channels, new AuditLog(), 1, 1);
        channels.clear();

        // Act
        dispatcher.dispatch(order("notification-immutable-channels"))
                .get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        assertEquals(1, deliveries.get());
    }

    @Test
    void givenCompletionAndTimeoutRace_whenAttemptSettles_thenExactlyOneOutcomeIsAudited() throws Exception {
        // Arrange
        CyclicBarrier settlementBarrier = new CyclicBarrier(2);
        CountDownLatch channelStarted = new CountDownLatch(1);
        NotificationChannel racingChannel = ignoredOrder -> {
            channelStarted.countDown();
            try {
                settlementBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("Channel settlement barrier failed", exception);
            }
        };
        AuditLog auditLog = new AuditLog();
        Order order = order("notification-race");
        NotificationDispatcher dispatcher =
                dispatcher(List.of(racingChannel), auditLog, 1, 1, Duration.ofDays(1));
        ExecutorService deadlineExecutor = testExecutor(Executors.newSingleThreadExecutor());

        // Act
        CompletableFuture<Void> dispatch = dispatcher.dispatch(order);
        boolean channelWasReady = channelStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        Runnable manualDeadline = dispatcher.deadlineTrigger(dispatch, racingChannel);
        var deadlineResult = deadlineExecutor.submit(() -> {
            settlementBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            manualDeadline.run();
            return true;
        });
        dispatch.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean deadlineAttempted = deadlineResult.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        List<AuditEvent> outcomes = auditLog.eventsFor(order.getId());

        // Assert
        assertTrue(channelWasReady);
        assertTrue(deadlineAttempted);
        assertEquals(1, outcomes.size());
        assertEquals(0, dispatcher.activeAttemptCount());
    }

    @Test
    void givenDispatchCompetesWithShutdown_whenLifecycleSettles_thenAllFuturesCompleteAndExecutorsTerminate()
            throws Exception {
        // Arrange
        CountDownLatch acceptedChannelStarted = new CountDownLatch(1);
        NotificationChannel blocked = ignoredOrder -> {
            acceptedChannelStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        AuditLog auditLog = new AuditLog();
        NotificationDispatcher dispatcher =
                dispatcher(List.of(blocked), auditLog, 1, 1, Duration.ofDays(1));
        Order acceptedOrder = order("notification-accepted-before-race");
        CompletableFuture<Void> acceptedDispatch = dispatcher.dispatch(acceptedOrder);
        boolean acceptedWasStarted =
                acceptedChannelStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        Order racingOrder = order("notification-lifecycle-race");
        CyclicBarrier lifecycleBarrier = new CyclicBarrier(2);
        ExecutorService contenders = testExecutor(Executors.newFixedThreadPool(2));

        // Act
        var racingDispatchResult = contenders.submit(() -> {
            lifecycleBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return dispatcher.dispatch(racingOrder);
        });
        var shutdownResult = contenders.submit(() -> {
            lifecycleBarrier.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            dispatcher.shutdown(TEST_WAIT);
            return true;
        });
        CompletableFuture<Void> racingDispatch =
                racingDispatchResult.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        acceptedDispatch.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        racingDispatch.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        boolean shutdownCompleted = shutdownResult.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        String racingOutcome = auditLog.eventsFor(racingOrder.getId()).getFirst().message();

        // Assert
        assertTrue(acceptedWasStarted);
        assertTrue(shutdownCompleted);
        assertTrue(acceptedDispatch.isDone());
        assertTrue(racingDispatch.isDone());
        assertTrue(racingOutcome.contains("during shutdown")
                || racingOutcome.contains("dispatcher is shut down"));
        assertEquals(0, dispatcher.activeAttemptCount());
        assertTrue(dispatcher.isTerminated());
    }

    @Test
    void givenDispatcherHasShutDown_whenDispatchIsRequested_thenShutdownCancellationIsAudited() throws Exception {
        // Arrange
        AuditLog auditLog = new AuditLog();
        NotificationDispatcher dispatcher =
                dispatcher(List.of(ignoredOrder -> { }), auditLog, 1, 1);
        dispatcher.shutdown();
        Order order = order("notification-after-shutdown");

        // Act
        dispatcher.dispatch(order).get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

        // Assert
        String message = auditLog.eventsFor(order.getId()).getFirst().message();
        assertTrue(message.contains("shutdown"));
        assertFalse(message.contains("capacity"));
    }

    @Test
    void givenNullChannelInList_whenConstructed_thenRejectsInvalidState() {
        // Arrange
        List<NotificationChannel> channels = new ArrayList<>();
        channels.add(null);

        // Act
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new NotificationDispatcher(channels, new AuditLog(), policy(1, 1, Duration.ofSeconds(1))));

        // Assert
        assertTrue(exception.getMessage().contains("channel"));
    }

    private NotificationDispatcher dispatcher(
            List<NotificationChannel> channels,
            AuditLog auditLog,
            int workerCount,
            int queueCapacity) {
        return dispatcher(channels, auditLog, workerCount, queueCapacity, Duration.ofSeconds(1));
    }

    private NotificationDispatcher dispatcher(
            List<NotificationChannel> channels,
            AuditLog auditLog,
            int workerCount,
            int queueCapacity,
            Duration deadline) {
        NotificationDispatcher dispatcher =
                new NotificationDispatcher(channels, auditLog, policy(workerCount, queueCapacity, deadline));
        dispatchers.add(dispatcher);
        return dispatcher;
    }

    private NotificationDispatcher dispatcher(
            List<NotificationChannel> channels,
            AuditLog auditLog,
            int workerCount,
            int queueCapacity,
            Duration deadline,
            ScheduledThreadPoolExecutor deadlineExecutor) {
        NotificationDispatcher dispatcher =
                new NotificationDispatcher(
                        channels,
                        auditLog,
                        policy(workerCount, queueCapacity, deadline),
                        deadlineExecutor);
        dispatchers.add(dispatcher);
        return dispatcher;
    }

    private ExecutorService testExecutor(ExecutorService testExecutor) {
        testExecutors.add(testExecutor);
        return testExecutor;
    }

    private List<LogRecord> captureNotificationLogs() {
        List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        installNotificationLogHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return records;
    }

    private void installNotificationLogHandler(Handler handler) {
        Logger.getLogger(NotificationDispatcher.class.getName()).addHandler(handler);
        notificationLogHandlers.add(handler);
    }

    private static OrderProcessingPolicy policy(int workerCount, int queueCapacity, Duration deadline) {
        return new OrderProcessingPolicy(
                1,
                1,
                1,
                1,
                workerCount,
                queueCapacity,
                Duration.ofSeconds(1),
                deadline,
                Duration.ofSeconds(1));
    }

    private static Order order(String orderId) {
        OrderItem item = new OrderItem("product-1", "Product", new BigDecimal("10.00"), 1);
        return new Order(orderId, "customer-1", List.of(item), item.getLineTotal(), Instant.EPOCH);
    }

    private static void awaitIgnoringInterruptUntilReleased(CountDownLatch releaseChannel) {
        boolean wasInterrupted = false;
        while (true) {
            try {
                releaseChannel.await();
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
