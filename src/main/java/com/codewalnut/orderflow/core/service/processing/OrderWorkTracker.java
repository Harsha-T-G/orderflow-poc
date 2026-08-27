package com.codewalnut.orderflow.core.service.processing;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public final class OrderWorkTracker {
    private final Object workMonitor = new Object();
    private final Set<String> acceptedOrderIds = new HashSet<>();
    private final Set<String> outstandingOrderIds = new HashSet<>();
    private int waitingCallers;

    public boolean begin(String orderId) {
        requireOrderId(orderId);
        synchronized (workMonitor) {
            if (!acceptedOrderIds.add(orderId)) {
                return false;
            }
            outstandingOrderIds.add(orderId);
            return true;
        }
    }

    public boolean complete(String orderId) {
        requireOrderId(orderId);
        synchronized (workMonitor) {
            if (!outstandingOrderIds.remove(orderId)) {
                return false;
            }
            if (outstandingOrderIds.isEmpty()) {
                workMonitor.notifyAll();
            }
            return true;
        }
    }

    boolean rollback(String orderId) {
        requireOrderId(orderId);
        synchronized (workMonitor) {
            boolean wasOutstanding = outstandingOrderIds.remove(orderId);
            boolean wasAccepted = acceptedOrderIds.remove(orderId);
            if (outstandingOrderIds.isEmpty()) {
                workMonitor.notifyAll();
            }
            return wasOutstanding && wasAccepted;
        }
    }

    public void awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = timeoutNanos(timeout);
        long waitStartedNanos = System.nanoTime();
        synchronized (workMonitor) {
            while (!outstandingOrderIds.isEmpty()) {
                long elapsedNanos = System.nanoTime() - waitStartedNanos;
                long remainingNanos = timeoutNanos - elapsedNanos;
                if (remainingNanos <= 0) {
                    throw timeoutException();
                }
                waitingCallers++;
                workMonitor.notifyAll();
                try {
                    TimeUnit.NANOSECONDS.timedWait(workMonitor, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                } finally {
                    waitingCallers--;
                }
            }
        }
    }

    void awaitWaitingCaller(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = timeoutNanos(timeout);
        long waitStartedNanos = System.nanoTime();
        synchronized (workMonitor) {
            while (waitingCallers == 0) {
                long elapsedNanos = System.nanoTime() - waitStartedNanos;
                long remainingNanos = timeoutNanos - elapsedNanos;
                if (remainingNanos <= 0) {
                    throw new IllegalStateException("Timed out waiting for an awaitIdle caller to block");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(workMonitor, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    private static long timeoutNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private IllegalStateException timeoutException() {
        return new IllegalStateException(
                "Timed out waiting for order processing to become idle; outstanding="
                        + outstandingOrderIds.size()
                        + " orderIds="
                        + new TreeSet<>(outstandingOrderIds));
    }

    private static void requireOrderId(String orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        if (orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
    }
}
