package com.codewalnut.orderflow.core.service.audit;

import com.codewalnut.orderflow.core.domain.audit.AuditEvent;
import com.codewalnut.orderflow.core.domain.audit.AuditEventType;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class AuditLog {
    private final ConcurrentLinkedQueue<AuditEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicLong nextEventId = new AtomicLong(1);
    private final Clock clock;
    private final Supplier<String> eventIdSupplier;

    public AuditLog() {
        this(Clock.systemUTC());
    }

    public AuditLog(Clock clock) {
        this(clock, null);
    }

    AuditLog(Clock clock, Supplier<String> eventIdSupplier) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.eventIdSupplier = eventIdSupplier;
    }

    public void record(String orderId, AuditEventType type, String message) {
        String eventId = eventIdSupplier == null
                ? String.valueOf(nextEventId.getAndIncrement())
                : eventIdSupplier.get();
        events.add(new AuditEvent(
                eventId,
                orderId,
                type,
                message,
                Instant.now(clock),
                Thread.currentThread().getName()));
    }

    public List<AuditEvent> eventsFor(String orderId) {
        return events.stream()
                .filter(event -> event.orderId().equals(orderId))
                .sorted(byTimestampThenId())
                .toList();
    }

    public List<AuditEvent> allEvents() {
        return events.stream()
                .sorted(byTimestampThenId())
                .toList();
    }

    private static Comparator<AuditEvent> byTimestampThenId() {
        return Comparator.comparing(AuditEvent::timestamp)
                .thenComparing(AuditLog::compareEventIds);
    }

    private static int compareEventIds(AuditEvent left, AuditEvent right) {
        if (isUnsignedLong(left.id()) && isUnsignedLong(right.id())) {
            return Long.compare(Long.parseLong(left.id()), Long.parseLong(right.id()));
        }
        return left.id().compareTo(right.id());
    }

    private static boolean isUnsignedLong(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return false;
        }
        for (int digitIndex = 0; digitIndex < eventId.length(); digitIndex++) {
            if (!Character.isDigit(eventId.charAt(digitIndex))) {
                return false;
            }
        }
        return true;
    }
}
