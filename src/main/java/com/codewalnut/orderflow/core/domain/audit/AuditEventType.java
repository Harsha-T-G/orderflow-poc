package com.codewalnut.orderflow.core.domain.audit;

public enum AuditEventType {
    CREATED,
    QUEUED,
    PROCESSING,
    VALIDATION,
    RESERVATION,
    PAYMENT,
    RELEASE,
    COMPLETED,
    FAILED,
    CANCELLED,
    NOTIFICATION,
    SKIPPED
}
