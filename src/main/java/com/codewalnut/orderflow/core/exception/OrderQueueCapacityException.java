package com.codewalnut.orderflow.core.exception;

import java.util.Objects;

public final class OrderQueueCapacityException extends OrderFlowException {
    public OrderQueueCapacityException(String orderId, String capacityStage, int queueCapacity) {
        super(message(orderId, capacityStage, queueCapacity));
    }

    private static String message(String orderId, String capacityStage, int queueCapacity) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(capacityStage, "capacityStage must not be null");
        if (orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (capacityStage.isBlank()) {
            throw new IllegalArgumentException("capacityStage must not be blank");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        return "Order " + orderId + " rejected because " + capacityStage
                + " queue capacity " + queueCapacity + " was reached";
    }
}
