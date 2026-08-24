package com.codewalnut.orderflow.core.service.processing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderWorkTrackerTest {

    @Test
    void givenUnseenOrderId_whenWorkBeginsTwice_thenOnlyFirstBeginIsAccepted() {
        // Arrange
        OrderWorkTracker tracker = new OrderWorkTracker();

        // Act
        boolean firstBeginAccepted = tracker.begin("ORDER-1");
        boolean secondBeginAccepted = tracker.begin("ORDER-1");

        // Assert
        assertTrue(firstBeginAccepted);
        assertFalse(secondBeginAccepted);
    }

    @Test
    void givenCompletedOrderId_whenWorkBeginsAgain_thenOrderIdRemainsRejected() {
        // Arrange
        OrderWorkTracker tracker = new OrderWorkTracker();
        tracker.begin("ORDER-1");
        tracker.complete("ORDER-1");

        // Act
        boolean repeatedBeginAccepted = tracker.begin("ORDER-1");

        // Assert
        assertFalse(repeatedBeginAccepted);
    }

    @Test
    void givenTrackedOrder_whenWorkCompletesTwice_thenOnlyFirstCompletionChangesTracking() throws Exception {
        // Arrange
        OrderWorkTracker tracker = new OrderWorkTracker();
        tracker.begin("ORDER-1");

        // Act
        boolean firstCompletionAccepted = tracker.complete("ORDER-1");
        boolean secondCompletionAccepted = tracker.complete("ORDER-1");

        // Assert
        assertTrue(firstCompletionAccepted);
        assertFalse(secondCompletionAccepted);
        assertDoesNotThrow(() -> tracker.awaitIdle(Duration.ZERO));
    }

    @Test
    void givenTrackedOrder_whenAwaitIdleDeadlineExpires_thenTimeoutContainsOutstandingContext() {
        // Arrange
        OrderWorkTracker tracker = new OrderWorkTracker();
        tracker.begin("ORDER-17");

        // Act
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> tracker.awaitIdle(Duration.ofMillis(20)));

        // Assert
        assertTrue(exception.getMessage().contains("ORDER-17"));
        assertTrue(exception.getMessage().contains("outstanding=1"));
    }

    @Test
    void givenAwaitingCaller_whenLastTrackedOrderCompletes_thenAwaitIdleReturns() throws Exception {
        // Arrange
        OrderWorkTracker tracker = new OrderWorkTracker();
        tracker.begin("ORDER-1");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread awaitingThread = new Thread(() -> {
            try {
                tracker.awaitIdle(Duration.ofSeconds(1));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        // Act
        awaitingThread.start();
        tracker.awaitWaitingCaller(Duration.ofSeconds(1));
        tracker.complete("ORDER-1");
        awaitingThread.join(1_000);

        // Assert
        assertFalse(awaitingThread.isAlive());
        assertTrue(failure.get() == null, () -> "Unexpected await failure: " + failure.get());
    }

    @Test
    void givenAwaitingCaller_whenInterrupted_thenInterruptedExceptionAndStatusArePreserved() throws Exception {
        // Arrange
        OrderWorkTracker tracker = new OrderWorkTracker();
        tracker.begin("ORDER-1");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptStatus = new AtomicBoolean();
        Thread awaitingThread = new Thread(() -> {
            try {
                tracker.awaitIdle(Duration.ofSeconds(5));
            } catch (Throwable throwable) {
                failure.set(throwable);
                interruptStatus.set(Thread.currentThread().isInterrupted());
            }
        });

        // Act
        awaitingThread.start();
        tracker.awaitWaitingCaller(Duration.ofSeconds(1));
        awaitingThread.interrupt();
        awaitingThread.join(1_000);

        // Assert
        assertFalse(awaitingThread.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(interruptStatus.get());
    }
}
