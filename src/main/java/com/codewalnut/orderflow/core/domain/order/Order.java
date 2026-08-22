package com.codewalnut.orderflow.core.domain.order;

import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import com.codewalnut.orderflow.core.exception.InvalidOrderStatusTransitionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Order {
    private final String id;
    private final String customerId;
    private final List<OrderItem> items;
    private final BigDecimal originalAmount;
    private final Instant createdAt;
    private OrderStatus status;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
    private String failureReason;

    public Order(String id, String customerId, List<OrderItem> items, BigDecimal originalAmount, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.items = List.copyOf(items);
        this.originalAmount = originalAmount.setScale(2, RoundingMode.HALF_UP);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = OrderStatus.CREATED;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized OrderStatus getStatus() {
        return status;
    }

    public synchronized Optional<BigDecimal> getDiscountAmount() {
        return Optional.ofNullable(discountAmount);
    }

    public synchronized Optional<BigDecimal> getFinalAmount() {
        return Optional.ofNullable(finalAmount);
    }

    public synchronized Optional<String> getFailureReason() {
        return Optional.ofNullable(failureReason);
    }

    public synchronized void queue() {
        requireTransition(OrderStatus.QUEUED, status == OrderStatus.CREATED);
        status = OrderStatus.QUEUED;
    }

    public synchronized void startProcessing() {
        requireTransition(OrderStatus.PROCESSING, status == OrderStatus.QUEUED);
        status = OrderStatus.PROCESSING;
    }

    public synchronized void complete(BigDecimal discountAmount, BigDecimal finalAmount) {
        requireTransition(OrderStatus.COMPLETED, status == OrderStatus.PROCESSING);
        BigDecimal normalizedDiscount = requireNonNegativeAmount(discountAmount, "discount amount");
        BigDecimal normalizedFinal = requireNonNegativeAmount(finalAmount, "final amount");
        this.discountAmount = normalizedDiscount;
        this.finalAmount = normalizedFinal;
        this.status = OrderStatus.COMPLETED;
    }

    public synchronized void fail(String reason) {
        requireTransition(OrderStatus.FAILED, status == OrderStatus.PROCESSING);
        if (reason == null || reason.isBlank()) {
            throw new InvalidOrderException("Failure reason must not be blank");
        }
        this.failureReason = reason;
        this.status = OrderStatus.FAILED;
    }

    public synchronized void cancel() {
        requireTransition(
                OrderStatus.CANCELLED,
                status == OrderStatus.CREATED || status == OrderStatus.QUEUED);
        status = OrderStatus.CANCELLED;
    }

    private void requireTransition(OrderStatus target, boolean allowed) {
        if (!allowed) {
            throw new InvalidOrderStatusTransitionException(
                    "Order " + id + " cannot transition from " + status + " to " + target);
        }
    }

    private static BigDecimal requireNonNegativeAmount(BigDecimal amount, String label) {
        if (amount == null) {
            throw new InvalidOrderException("Order " + label + " must not be null");
        }
        BigDecimal normalized = amount.setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() < 0) {
            throw new InvalidOrderException("Order " + label + " must not be negative");
        }
        return normalized;
    }
}
