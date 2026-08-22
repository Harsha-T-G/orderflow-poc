package com.codewalnut.orderflow.core.service.audit;

import com.codewalnut.orderflow.core.domain.audit.AuditEvent;
import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
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
        for (int i = 0; i < threadCount; i++) {
            String orderId = "order-" + i;
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
        for (int i = 1; i < ordered.size(); i++) {
            AuditEvent previous = ordered.get(i - 1);
            AuditEvent current = ordered.get(i);
            int comparison = previous.timestamp().compareTo(current.timestamp());
            assertTrue(comparison < 0 || (comparison == 0 && previous.id().compareTo(current.id()) <= 0));
        }
    }
}
