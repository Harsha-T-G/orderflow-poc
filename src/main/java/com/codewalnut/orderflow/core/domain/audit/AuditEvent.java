package com.codewalnut.orderflow.core.domain.audit;

import java.time.Instant;
import java.util.Objects;

public record AuditEvent(
        String id,
        String orderId,
        AuditEventType type,
        String message,
        Instant timestamp,
        String threadName) {

    public AuditEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Audit event ID must not be blank");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Audit event order ID must not be blank");
        }
        Objects.requireNonNull(type, "Audit event type must not be null");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Audit event message must not be blank");
        }
        Objects.requireNonNull(timestamp, "Audit event timestamp must not be null");
        if (threadName == null || threadName.isBlank()) {
            throw new IllegalArgumentException("Audit event thread name must not be blank");
        }
    }
}
