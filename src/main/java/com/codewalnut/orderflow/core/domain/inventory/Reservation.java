package com.codewalnut.orderflow.core.domain.inventory;

import java.util.Map;
import java.util.Objects;

public record Reservation(String orderId, Map<String, Integer> reservedQuantities) {
    public Reservation {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Reservation order ID must not be blank");
        }
        Objects.requireNonNull(reservedQuantities, "reservedQuantities must not be null");
        reservedQuantities = Map.copyOf(reservedQuantities);
    }
}
