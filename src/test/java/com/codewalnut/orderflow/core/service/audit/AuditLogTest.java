package com.codewalnut.orderflow.core.service.audit;

import com.codewalnut.orderflow.core.domain.audit.AuditEvent;
import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    @Test
    void givenEvent_whenRecorded_thenQueryReturnsImmutableCopyWithTimestampAndThread() {
        // Arrange
        AuditLog auditLog = new AuditLog();

        // Act
        auditLog.record("order-1", AuditEventType.CREATED, "Order created");
        List<AuditEvent> events = auditLog.eventsFor("order-1");

        // Assert
        assertEquals(1, events.size());
        AuditEvent event = events.getFirst();
        assertEquals("order-1", event.orderId());
        assertEquals(AuditEventType.CREATED, event.type());
        assertEquals("Order created", event.message());
        assertTrue(event.id() != null && !event.id().isBlank());
        assertTrue(event.timestamp().isBefore(Instant.now().plusSeconds(1)));
        assertEquals(Thread.currentThread().getName(), event.threadName());
        assertThrows(UnsupportedOperationException.class, () -> events.add(event));
    }

    @Test
    void givenEventsWithSameTimestamp_whenQueried_thenEventIdIsTheTieBreaker() {
        // Arrange
        Instant fixedTime = Instant.parse("2026-08-21T10:00:00Z");
        java.util.concurrent.atomic.AtomicInteger descendingIds = new java.util.concurrent.atomic.AtomicInteger(2);
        AuditLog auditLog = new AuditLog(
                Clock.fixed(fixedTime, java.time.ZoneOffset.UTC),
                () -> String.valueOf(descendingIds.getAndDecrement()));

        // Act
        auditLog.record("order-1", AuditEventType.QUEUED, "queued first recorded");
        auditLog.record("order-1", AuditEventType.PROCESSING, "processing second recorded");
        List<AuditEvent> events = auditLog.eventsFor("order-1");

        // Assert
        assertEquals("1", events.get(0).id());
        assertEquals("2", events.get(1).id());
        assertEquals(AuditEventType.PROCESSING, events.get(0).type());
        assertEquals(AuditEventType.QUEUED, events.get(1).type());
    }

    @Test
    void givenTwoDigitEventIdsWithTheSameTimestamp_whenQueried_thenNumericIdOrderIsUsed() {
        // Arrange
        Instant fixedTime = Instant.parse("2026-08-21T10:00:00Z");
        AuditLog auditLog = new AuditLog(Clock.fixed(fixedTime, java.time.ZoneOffset.UTC));
        for (int eventIndex = 0; eventIndex < 11; eventIndex++) {
            auditLog.record("order-1", AuditEventType.CREATED, "event " + (eventIndex + 1));
        }

        // Act
        List<AuditEvent> events = auditLog.eventsFor("order-1");

        // Assert
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"),
                events.stream().map(AuditEvent::id).toList());
    }

    @Test
    void givenNonnumericEventIdsWithTheSameTimestamp_whenQueried_thenLexicographicOrderIsUsed() {
        // Arrange
        AuditLog auditLog = auditLogWithIds("beta", "alpha", "gamma");

        // Act
        recordEvents(auditLog, 3);
        List<String> eventIds = auditLog.allEvents().stream().map(AuditEvent::id).toList();

        // Assert
        assertEquals(List.of("alpha", "beta", "gamma"), eventIds);
    }

    @Test
    void givenMixedEventIdsWithTheSameTimestamp_whenQueried_thenNumericIdsPrecedeNonnumericIds() {
        // Arrange
        AuditLog auditLog = auditLogWithIds("1a", "10", "A", "2");

        // Act
        recordEvents(auditLog, 4);
        List<String> eventIds = auditLog.allEvents().stream().map(AuditEvent::id).toList();

        // Assert
        assertEquals(List.of("2", "10", "1a", "A"), eventIds);
    }

    @Test
    void givenLeadingZeroNumericEventIdsWithTheSameTimestamp_whenQueried_thenNumericValueAndTextOrderAreUsed() {
        // Arrange
        AuditLog auditLog = auditLogWithIds("1", "01", "000", "001");

        // Act
        recordEvents(auditLog, 4);
        List<String> eventIds = auditLog.allEvents().stream().map(AuditEvent::id).toList();

        // Assert
        assertEquals(List.of("000", "001", "01", "1"), eventIds);
    }

    @Test
    void givenArbitrarilyLongNumericEventIdsWithTheSameTimestamp_whenQueried_thenOrderingDoesNotOverflow() {
        // Arrange
        String smallerId = "9999999999999999999999999999999999999999";
        String largerId = "10000000000000000000000000000000000000000";
        AuditLog auditLog = auditLogWithIds(largerId, smallerId);

        // Act
        recordEvents(auditLog, 2);
        List<String> eventIds = auditLog.allEvents().stream().map(AuditEvent::id).toList();

        // Assert
        assertEquals(List.of(smallerId, largerId), eventIds);
    }

    @Test
    void givenNumericAndNonnumericIdsThatFormLexicalCycles_whenQueried_thenComparatorRemainsTransitive() {
        // Arrange
        AuditLog auditLog = auditLogWithIds("2", "10", "1a", "02", "010", "a", "0002", "9", "z");

        // Act
        recordEvents(auditLog, 9);
        List<String> eventIds = auditLog.allEvents().stream().map(AuditEvent::id).toList();

        // Assert
        assertEquals(List.of("0002", "02", "2", "9", "010", "10", "1a", "a", "z"), eventIds);
    }

    @Test
    void givenNoEvents_whenQueried_thenReturnsEmptyImmutableList() {
        // Arrange
        AuditLog auditLog = new AuditLog();

        // Act
        List<AuditEvent> events = auditLog.allEvents();

        // Assert
        assertTrue(events.isEmpty());
        assertThrows(UnsupportedOperationException.class, events::clear);
    }

    @Test
    void givenConcurrentRecordings_whenQueried_thenEveryEventIsPresentAndOrdered() throws Exception {
        // Arrange
        AuditLog auditLog = new AuditLog();
        int threadCount = 20;
        CyclicBarrier start = new CyclicBarrier(threadCount);
        CountDownLatch finished = new CountDownLatch(threadCount);

        // Act
        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            String orderId = "order-" + threadIndex;
            Thread thread = new Thread(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    auditLog.record(orderId, AuditEventType.CREATED, "created " + orderId);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                } finally {
                    finished.countDown();
                }
            });
            thread.start();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));

        // Assert
        assertEquals(threadCount, auditLog.allEvents().size());
        List<AuditEvent> ordered = auditLog.allEvents();
        for (int eventIndex = 1; eventIndex < ordered.size(); eventIndex++) {
            AuditEvent previous = ordered.get(eventIndex - 1);
            AuditEvent current = ordered.get(eventIndex);
            int comparison = previous.timestamp().compareTo(current.timestamp());
            assertTrue(comparison < 0 || (comparison == 0
                    && Long.parseLong(previous.id()) <= Long.parseLong(current.id())));
        }
    }

    private static AuditLog auditLogWithIds(String... eventIds) {
        ArrayDeque<String> remainingIds = new ArrayDeque<>(List.of(eventIds));
        return new AuditLog(
                Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC),
                remainingIds::removeFirst);
    }

    private static void recordEvents(AuditLog auditLog, int eventCount) {
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            auditLog.record("order-1", AuditEventType.CREATED, "event " + eventIndex);
        }
    }
}
