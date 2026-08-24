package com.codewalnut.orderflow.core.service.processing;

import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.exception.PaymentFailedException;
import com.codewalnut.orderflow.core.service.payment.PaymentGateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class PaymentCoordinator {

    private final PaymentGateway paymentGateway;
    private final Duration paymentDeadline;
    private final Duration shutdownBudget;
    private final ThreadPoolExecutor paymentExecutor;
    private final ScheduledThreadPoolExecutor deadlineExecutor;
    private final Set<PaymentAttempt> attempts = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private boolean acceptingPayments = true;

    PaymentCoordinator(PaymentGateway paymentGateway, OrderProcessingPolicy policy) {
        this(
                paymentGateway,
                policy,
                new ScheduledThreadPoolExecutor(1, namedThreads("order-payment-deadline-")));
    }

    PaymentCoordinator(
            PaymentGateway paymentGateway,
            OrderProcessingPolicy policy,
            ScheduledThreadPoolExecutor deadlineExecutor) {
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        paymentDeadline = policy.paymentDeadline();
        shutdownBudget = policy.shutdownBudget();
        paymentExecutor = new ThreadPoolExecutor(
                policy.paymentWorkerCount(),
                policy.paymentWorkerCount(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(policy.paymentQueueCapacity()),
                namedThreads("order-payment-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.deadlineExecutor = Objects.requireNonNull(deadlineExecutor, "deadlineExecutor must not be null");
        this.deadlineExecutor.setRemoveOnCancelPolicy(true);
    }

    CompletableFuture<PaymentOutcome> charge(Order order, BigDecimal amount) {
        Objects.requireNonNull(order, "order must not be null");
        BigDecimal normalizedAmount = requireNonNegativeAmount(amount);
        PaymentAttempt attempt = new PaymentAttempt(order);
        FutureTask<Void> paymentTask = new FutureTask<>(() -> {
            invokeGateway(attempt, normalizedAmount);
            return null;
        });
        attempt.paymentTask = paymentTask;

        synchronized (lifecycleMonitor) {
            if (!acceptingPayments) {
                attempt.settle(new PaymentOutcome(
                        PaymentOutcome.Kind.REJECTED,
                        "Payment coordinator is shut down; rejected order " + order.getId(),
                        null));
                return attempt.outcome;
            }
            attempts.add(attempt);
            try {
                paymentExecutor.execute(paymentTask);
            } catch (RejectedExecutionException exception) {
                attempt.settle(new PaymentOutcome(
                        PaymentOutcome.Kind.REJECTED,
                        "Payment queue is full; rejected order " + order.getId(),
                        exception));
                return attempt.outcome;
            }
            if (!attempt.isSettled()) {
                scheduleDeadline(attempt);
            }
        }
        return attempt.outcome;
    }

    void shutdown() {
        shutdown(shutdownBudget);
    }

    void shutdown(Duration remainingDuration) {
        Duration validatedDuration = requireNonNegative(remainingDuration, "remainingDuration");
        long shutdownStartedNanos = System.nanoTime();
        synchronized (lifecycleMonitor) {
            acceptingPayments = false;
            for (PaymentAttempt attempt : List.copyOf(attempts)) {
                attempt.cancelForShutdown();
            }
            paymentExecutor.shutdownNow();
            deadlineExecutor.shutdownNow();
        }
        awaitTermination(validatedDuration, shutdownStartedNanos);
    }

    boolean isTerminated() {
        return paymentExecutor.isTerminated() && deadlineExecutor.isTerminated();
    }

    int activeAttemptCount() {
        return attempts.size();
    }

    Runnable deadlineTrigger(CompletableFuture<PaymentOutcome> payment) {
        Objects.requireNonNull(payment, "payment must not be null");
        for (PaymentAttempt attempt : attempts) {
            if (attempt.outcome == payment) {
                return attempt::timeOut;
            }
        }
        throw new IllegalArgumentException("Payment is not an active coordinator attempt");
    }

    private void invokeGateway(PaymentAttempt attempt, BigDecimal amount) {
        try {
            paymentGateway.charge(attempt.order, amount);
            attempt.settle(new PaymentOutcome(
                    PaymentOutcome.Kind.SUCCESS,
                    "Payment succeeded for order " + attempt.order.getId(),
                    null));
        } catch (PaymentFailedException exception) {
            attempt.settle(new PaymentOutcome(
                    PaymentOutcome.Kind.DECLARED_FAILURE,
                    exception.getMessage(),
                    exception));
        } catch (RuntimeException exception) {
            String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            attempt.settle(new PaymentOutcome(
                    PaymentOutcome.Kind.UNEXPECTED_FAILURE,
                    "Payment execution failed for order " + attempt.order.getId() + ": " + detail,
                    exception));
        }
    }

    private void scheduleDeadline(PaymentAttempt attempt) {
        try {
            Future<?> deadlineTask = deadlineExecutor.schedule(
                    attempt::timeOut,
                    paymentDeadline.toNanos(),
                    TimeUnit.NANOSECONDS);
            attempt.deadlineTask = deadlineTask;
            if (attempt.isSettled()) {
                deadlineTask.cancel(false);
            }
        } catch (RejectedExecutionException exception) {
            attempt.rejectDeadline(exception);
        }
    }

    private void awaitTermination(Duration remainingDuration, long shutdownStartedNanos) {
        long shutdownBudgetNanos = toNanosOrMaximum(remainingDuration);
        try {
            awaitUntil(paymentExecutor, shutdownStartedNanos, shutdownBudgetNanos);
            awaitUntil(deadlineExecutor, shutdownStartedNanos, shutdownBudgetNanos);
        } catch (InterruptedException exception) {
            paymentExecutor.shutdownNow();
            deadlineExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUntil(
            ThreadPoolExecutor executor,
            long shutdownStartedNanos,
            long shutdownBudgetNanos) throws InterruptedException {
        executor.awaitTermination(
                remainingNanos(shutdownStartedNanos, shutdownBudgetNanos),
                TimeUnit.NANOSECONDS);
    }

    private static void awaitUntil(
            ScheduledThreadPoolExecutor executor,
            long shutdownStartedNanos,
            long shutdownBudgetNanos)
            throws InterruptedException {
        executor.awaitTermination(
                remainingNanos(shutdownStartedNanos, shutdownBudgetNanos),
                TimeUnit.NANOSECONDS);
    }

    private static long remainingNanos(long shutdownStartedNanos, long shutdownBudgetNanos) {
        long elapsedNanos = System.nanoTime() - shutdownStartedNanos;
        return Math.max(0L, shutdownBudgetNanos - Math.max(0L, elapsedNanos));
    }

    private static long toNanosOrMaximum(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static Duration requireNonNegative(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName + " must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return duration;
    }

    private static BigDecimal requireNonNegativeAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);
        if (normalizedAmount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return normalizedAmount;
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> new Thread(runnable, prefix + sequence.getAndIncrement());
    }

    private final class PaymentAttempt {

        private final Order order;
        private final CompletableFuture<PaymentOutcome> outcome = new CompletableFuture<>();
        private final AtomicBoolean settled = new AtomicBoolean();
        private volatile FutureTask<Void> paymentTask;
        private volatile Future<?> deadlineTask;

        private PaymentAttempt(Order order) {
            this.order = order;
        }

        private boolean settle(PaymentOutcome paymentOutcome) {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            publish(paymentOutcome);
            return true;
        }

        private boolean isSettled() {
            return settled.get();
        }

        private void publish(PaymentOutcome paymentOutcome) {
            Future<?> scheduledDeadline = deadlineTask;
            if (scheduledDeadline != null) {
                scheduledDeadline.cancel(false);
            }
            attempts.remove(this);
            outcome.complete(paymentOutcome);
        }

        private void timeOut() {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            cancelTask();
            publish(new PaymentOutcome(
                    PaymentOutcome.Kind.TIMED_OUT,
                    "Payment timed out for order " + order.getId() + " after " + paymentDeadline,
                    null));
        }

        private void cancelForShutdown() {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            cancelTask();
            publish(new PaymentOutcome(
                    PaymentOutcome.Kind.CANCELLED,
                    "Payment cancelled during shutdown for order " + order.getId(),
                    null));
        }

        private void rejectDeadline(RejectedExecutionException exception) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            cancelTask();
            publish(new PaymentOutcome(
                    PaymentOutcome.Kind.REJECTED,
                    "Payment deadline scheduling rejected for order " + order.getId(),
                    exception));
        }

        private void cancelTask() {
            FutureTask<Void> task = paymentTask;
            if (task != null) {
                task.cancel(true);
                paymentExecutor.remove(task);
                paymentExecutor.purge();
            }
        }
    }
}
