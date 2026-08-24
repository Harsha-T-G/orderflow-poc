package com.codewalnut.orderflow.core.service.notification;

import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.processing.OrderProcessingPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NotificationDispatcher {

    private static final Logger LOGGER = Logger.getLogger(NotificationDispatcher.class.getName());

    private final List<NotificationChannel> channels;
    private final AuditLog auditLog;
    private final Duration notificationDeadline;
    private final Duration shutdownBudget;
    private final ThreadPoolExecutor notificationExecutor;
    private final ScheduledThreadPoolExecutor deadlineExecutor;
    private final Set<NotificationAttempt> attempts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private boolean acceptingNotifications = true;

    public NotificationDispatcher(
            List<NotificationChannel> channels,
            AuditLog auditLog,
            OrderProcessingPolicy policy) {
        this(
                channels,
                auditLog,
                policy,
                new ScheduledThreadPoolExecutor(1, namedThreads("order-notification-deadline-")));
    }

    NotificationDispatcher(
            List<NotificationChannel> channels,
            AuditLog auditLog,
            OrderProcessingPolicy policy,
            ScheduledThreadPoolExecutor deadlineExecutor) {
        this.channels = immutableChannels(channels);
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        notificationDeadline = policy.notificationDeadline();
        shutdownBudget = policy.shutdownBudget();
        notificationExecutor = new ThreadPoolExecutor(
                policy.notificationWorkerCount(),
                policy.notificationWorkerCount(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(policy.notificationQueueCapacity()),
                namedThreads("order-notification-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.deadlineExecutor = Objects.requireNonNull(deadlineExecutor, "deadlineExecutor must not be null");
        this.deadlineExecutor.setRemoveOnCancelPolicy(true);
    }

    public CompletableFuture<Void> dispatch(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (channels.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        DispatchGroup group = new DispatchGroup(channels.size());
        synchronized (lifecycleMonitor) {
            if (!acceptingNotifications) {
                for (NotificationChannel channel : channels) {
                    new NotificationAttempt(order, channel, group).cancelBeforeAcceptance();
                }
                return group.completion;
            }

            for (NotificationChannel channel : channels) {
                submit(order, channel, group);
            }
        }
        return group.completion;
    }

    public void shutdown() {
        shutdown(shutdownBudget);
    }

    public void shutdown(Duration remainingDuration) {
        Duration validatedDuration = requireNonNegative(remainingDuration, "remainingDuration");
        long shutdownStartedNanos = System.nanoTime();
        synchronized (lifecycleMonitor) {
            acceptingNotifications = false;
            for (NotificationAttempt attempt : List.copyOf(attempts)) {
                attempt.cancelForShutdown();
            }
            notificationExecutor.shutdownNow();
            deadlineExecutor.shutdownNow();
        }
        awaitTermination(validatedDuration, shutdownStartedNanos);
    }

    public boolean isTerminated() {
        return notificationExecutor.isTerminated() && deadlineExecutor.isTerminated();
    }

    int activeAttemptCount() {
        return attempts.size();
    }

    Runnable deadlineTrigger(CompletableFuture<Void> dispatch, NotificationChannel channel) {
        Objects.requireNonNull(dispatch, "dispatch must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        for (NotificationAttempt attempt : attempts) {
            if (attempt.group.completion == dispatch && attempt.channel == channel) {
                return attempt::timeOut;
            }
        }
        throw new IllegalArgumentException("Dispatch has no active attempt for the requested channel");
    }

    private void submit(Order order, NotificationChannel channel, DispatchGroup group) {
        NotificationAttempt attempt = new NotificationAttempt(order, channel, group);
        FutureTask<Void> deliveryTask = new FutureTask<>(() -> {
            invokeChannel(attempt);
            return null;
        });
        attempt.deliveryTask = deliveryTask;
        attempts.add(attempt);

        try {
            notificationExecutor.execute(deliveryTask);
        } catch (RejectedExecutionException exception) {
            attempt.rejectCapacity(exception);
            return;
        }

        if (!attempt.isSettled()) {
            scheduleDeadline(attempt);
        }
    }

    private void invokeChannel(NotificationAttempt attempt) {
        try {
            attempt.channel.deliver(attempt.order);
            attempt.succeed();
        } catch (RuntimeException exception) {
            attempt.fail(exception);
        }
    }

    private void scheduleDeadline(NotificationAttempt attempt) {
        try {
            Future<?> deadlineTask = deadlineExecutor.schedule(
                    attempt::timeOut,
                    notificationDeadline.toNanos(),
                    TimeUnit.NANOSECONDS);
            attempt.deadlineTask = deadlineTask;
            if (attempt.isSettled()) {
                deadlineTask.cancel(false);
            }
        } catch (RejectedExecutionException exception) {
            attempt.rejectDeadline(exception);
        }
    }

    private void recordOutcome(NotificationAttempt attempt, Level level, String message, Throwable cause) {
        logSafely(level, message, cause);
        try {
            auditLog.record(attempt.order.getId(), AuditEventType.NOTIFICATION, message);
        } catch (RuntimeException auditFailure) {
            logSafely(
                    Level.SEVERE,
                    "Audit recording failed for notification outcome on order " + attempt.order.getId(),
                    auditFailure);
        }
    }

    private static void logSafely(Level level, String message, Throwable cause) {
        try {
            if (cause == null) {
                LOGGER.log(level, message);
            } else {
                LOGGER.log(level, message, cause);
            }
        } catch (RuntimeException loggingFailure) {
            // JUL handlers are external callbacks; notification settlement cannot depend on them.
        }
    }

    private void awaitTermination(Duration remainingDuration, long shutdownStartedNanos) {
        long shutdownBudgetNanos = toNanosOrMaximum(remainingDuration);
        try {
            awaitUntil(notificationExecutor, shutdownStartedNanos, shutdownBudgetNanos);
            awaitUntil(deadlineExecutor, shutdownStartedNanos, shutdownBudgetNanos);
        } catch (InterruptedException exception) {
            notificationExecutor.shutdownNow();
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

    private static List<NotificationChannel> immutableChannels(List<NotificationChannel> channels) {
        Objects.requireNonNull(channels, "channels must not be null");
        List<NotificationChannel> validatedChannels = new ArrayList<>(channels.size());
        for (NotificationChannel channel : channels) {
            validatedChannels.add(Objects.requireNonNull(channel, "notification channel must not be null"));
        }
        return List.copyOf(validatedChannels);
    }

    private static Duration requireNonNegative(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName + " must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return duration;
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> new Thread(runnable, prefix + sequence.getAndIncrement());
    }

    private static String channelName(NotificationChannel channel) {
        String simpleName = channel.getClass().getSimpleName();
        return simpleName.isBlank() ? channel.getClass().getName() : simpleName;
    }

    private static String failureDetail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private final class NotificationAttempt {

        private final Order order;
        private final NotificationChannel channel;
        private final DispatchGroup group;
        private final AtomicBoolean settled = new AtomicBoolean();
        private volatile FutureTask<Void> deliveryTask;
        private volatile Future<?> deadlineTask;

        private NotificationAttempt(Order order, NotificationChannel channel, DispatchGroup group) {
            this.order = order;
            this.channel = channel;
            this.group = group;
        }

        private boolean isSettled() {
            return settled.get();
        }

        private void succeed() {
            settle(
                    Level.INFO,
                    "Notification succeeded via " + channelName(channel) + " for order " + order.getId(),
                    null,
                    false);
        }

        private void fail(RuntimeException exception) {
            settle(
                    Level.WARNING,
                    "Notification failed via " + channelName(channel) + " for order " + order.getId()
                            + ": " + failureDetail(exception),
                    exception,
                    false);
        }

        private void timeOut() {
            settle(
                    Level.WARNING,
                    "Notification timed out via " + channelName(channel) + " for order " + order.getId()
                            + " after " + notificationDeadline,
                    null,
                    true);
        }

        private void rejectCapacity(RejectedExecutionException exception) {
            settle(
                    Level.WARNING,
                    "Notification capacity rejected via " + channelName(channel) + " for order " + order.getId(),
                    exception,
                    true);
        }

        private void rejectDeadline(RejectedExecutionException exception) {
            settle(
                    Level.WARNING,
                    "Notification deadline scheduling rejected via " + channelName(channel)
                            + " for order " + order.getId(),
                    exception,
                    true);
        }

        private void cancelForShutdown() {
            settle(
                    Level.WARNING,
                    "Notification cancelled during shutdown via " + channelName(channel)
                            + " for order " + order.getId(),
                    null,
                    true);
        }

        private void cancelBeforeAcceptance() {
            settle(
                    Level.WARNING,
                    "Notification cancelled because dispatcher is shut down via " + channelName(channel)
                            + " for order " + order.getId(),
                    null,
                    false);
        }

        private void settle(Level level, String message, Throwable cause, boolean shouldCancelDelivery) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            try {
                if (shouldCancelDelivery) {
                    cancelDelivery();
                }
                Future<?> scheduledDeadline = deadlineTask;
                if (scheduledDeadline != null) {
                    scheduledDeadline.cancel(false);
                }
                attempts.remove(this);
                recordOutcome(this, level, message, cause);
            } finally {
                attempts.remove(this);
                group.completeOne();
            }
        }

        private void cancelDelivery() {
            FutureTask<Void> task = deliveryTask;
            if (task != null) {
                task.cancel(true);
                notificationExecutor.remove(task);
            }
        }
    }

    private static final class DispatchGroup {

        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicInteger remainingAttempts;

        private DispatchGroup(int attemptCount) {
            remainingAttempts = new AtomicInteger(attemptCount);
        }

        private void completeOne() {
            if (remainingAttempts.decrementAndGet() == 0) {
                completion.complete(null);
            }
        }
    }
}
