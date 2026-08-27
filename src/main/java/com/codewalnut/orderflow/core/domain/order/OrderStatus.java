package com.codewalnut.orderflow.core.domain.order;

public enum OrderStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
