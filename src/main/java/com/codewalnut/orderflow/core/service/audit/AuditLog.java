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
                .thenComparing(
                        AuditEvent::id,
                        AuditLog::compareEventIds);
    }

    private static int compareEventIds(String leftId, String rightId) {
        boolean isLeftNumeric = isNumericId(leftId);
        boolean isRightNumeric = isNumericId(rightId);
        if (isLeftNumeric != isRightNumeric) {
            return isLeftNumeric ? -1 : 1;
        }
        if (!isLeftNumeric) {
            return leftId.compareTo(rightId);
        }

        int leftSignificantStart = firstSignificantDigitIndex(leftId);
        int rightSignificantStart = firstSignificantDigitIndex(rightId);
        int significantLengthComparison = Integer.compare(
                leftId.length() - leftSignificantStart,
                rightId.length() - rightSignificantStart);
        if (significantLengthComparison != 0) {
            return significantLengthComparison;
        }

        for (int digitOffset = 0; digitOffset < leftId.length() - leftSignificantStart; digitOffset++) {
            int digitComparison = Character.compare(
                    leftId.charAt(leftSignificantStart + digitOffset),
                    rightId.charAt(rightSignificantStart + digitOffset));
            if (digitComparison != 0) {
                return digitComparison;
            }
        }
        return leftId.compareTo(rightId);
    }

    private static boolean isNumericId(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return false;
        }
        for (int digitIndex = 0; digitIndex < eventId.length(); digitIndex++) {
            char character = eventId.charAt(digitIndex);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static int firstSignificantDigitIndex(String eventId) {
        int digitIndex = 0;
        while (digitIndex < eventId.length() && eventId.charAt(digitIndex) == '0') {
            digitIndex++;
        }
        return digitIndex;
    }
}
