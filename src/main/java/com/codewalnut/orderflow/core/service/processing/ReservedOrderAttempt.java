package com.codewalnut.orderflow.core.service.processing;

import com.codewalnut.orderflow.core.domain.inventory.Reservation;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.pricing.DiscountResult;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class ReservedOrderAttempt {

    private final Order order;
    private final Reservation reservation;
    private final DiscountResult pricing;
    private final AtomicBoolean settled = new AtomicBoolean();

    ReservedOrderAttempt(Order order, Reservation reservation, DiscountResult pricing) {
        this.order = Objects.requireNonNull(order, "order must not be null");
        this.reservation = Objects.requireNonNull(reservation, "reservation must not be null");
        this.pricing = Objects.requireNonNull(pricing, "pricing must not be null");
        if (!order.getId().equals(reservation.orderId())) {
            throw new IllegalArgumentException(
                    "Reservation " + reservation.orderId() + " does not belong to order " + order.getId());
        }
    }

    Order order() {
        return order;
    }

    Reservation reservation() {
        return reservation;
    }

    DiscountResult pricing() {
        return pricing;
    }

    boolean trySettle() {
        return settled.compareAndSet(false, true);
    }
}
