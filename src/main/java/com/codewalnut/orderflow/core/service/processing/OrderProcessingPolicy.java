package com.codewalnut.orderflow.core.service.processing;

import java.time.Duration;
import java.util.Objects;

public record OrderProcessingPolicy(
        int orderWorkerCount,
        int orderQueueCapacity,
        int paymentWorkerCount,
        int paymentQueueCapacity,
        int notificationWorkerCount,
        int notificationQueueCapacity,
        Duration paymentDeadline,
        Duration notificationDeadline,
        Duration shutdownBudget) {

    private static final int DEFAULT_WORKER_COUNT = 3;
    private static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final Duration DEFAULT_PAYMENT_DEADLINE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_NOTIFICATION_DEADLINE = Duration.ofSeconds(2);
    private static final Duration DEFAULT_SHUTDOWN_BUDGET = Duration.ofSeconds(10);

    public OrderProcessingPolicy {
        requirePositive(orderWorkerCount, "orderWorkerCount");
        requirePositive(orderQueueCapacity, "orderQueueCapacity");
        requirePositive(paymentWorkerCount, "paymentWorkerCount");
        requirePositive(paymentQueueCapacity, "paymentQueueCapacity");
        requirePositive(notificationWorkerCount, "notificationWorkerCount");
        requirePositive(notificationQueueCapacity, "notificationQueueCapacity");
        paymentDeadline = requirePositive(paymentDeadline, "paymentDeadline");
        notificationDeadline = requirePositive(notificationDeadline, "notificationDeadline");
        shutdownBudget = requirePositive(shutdownBudget, "shutdownBudget");
    }

    public static OrderProcessingPolicy defaults() {
        return new OrderProcessingPolicy(
                DEFAULT_WORKER_COUNT,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_WORKER_COUNT,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_WORKER_COUNT,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_PAYMENT_DEADLINE,
                DEFAULT_NOTIFICATION_DEADLINE,
                DEFAULT_SHUTDOWN_BUDGET);
    }

    private static void requirePositive(int candidate, String fieldName) {
        if (candidate <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static Duration requirePositive(Duration candidate, String fieldName) {
        Objects.requireNonNull(candidate, fieldName + " must not be null");
        if (candidate.isZero() || candidate.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return candidate;
    }
}
