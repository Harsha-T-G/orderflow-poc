# OrderFlow Fulfilment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `test-driven-development`. Every behavior follows RED → GREEN → REFACTOR.
> Do not commit unless the user explicitly requests a commit.

**Status:** Implemented — 2026-08-21

**Goal:** Implement `REQ-060`–`REQ-090` and `AC-040`–`AC-060`, `AC-080`:
concurrent at-most-once processing, reservation/compensation, replaceable
payment and notification, contextual exceptions, and graceful shutdown.

**Architecture:** Models stay in `core.domain`; operations in `core.service`;
errors in `core.exception`. A `BlockingQueue` feeds a fixed worker pool.
Inventory reservation uses `ConcurrentHashMap.compute`, a deterministic
product-id journal, and exact release. Payment and notification run on
dedicated executors. Notification failure cannot change a final order state.

**Tech stack:** Java 21, Maven Wrapper, JUnit 5, JDK collections, executors,
`java.util.logging`, and functional interfaces only.

## Global constraints

- Read `SPEC.md`, `CONTEXT.md`, and `docs/specs/orderflow/02-fulfilment.md`
  before each task.
- Production names instead of restating comments; tests Given-When-Then with
  `// Arrange`, `// Act`, `// Assert`.
- Money uses `BigDecimal`, scale 2, `RoundingMode.HALF_UP`.
- No Spring, Lombok, persistence, networking, or new dependencies.
- No thread-per-order, busy waiting, or `Thread.stop`.
- Concurrency tests coordinate with latches/barriers/futures and bounded
  timeouts; do not use sleep as the only coordination.
- Do not commit unless the user explicitly requests a commit.

## Planned file structure

```text
core.exception
  InactiveProductException
  InsufficientStockException
  DuplicateOrderSubmissionException
  PaymentFailedException

core.domain.inventory
  Reservation            record: orderId + immutable reserved quantities

core.domain.audit
  AuditEventType
  AuditEvent             record

core.domain.payment
  (none beyond gateway usage of Order)

core.service.inventory
  Inventory              + reserve / release

core.service.audit
  AuditLog

core.service.payment
  PaymentGateway
  AlwaysSuccessfulPaymentGateway
  ConfigurableFailurePaymentGateway

core.service.notification
  NotificationChannel
  ConsoleNotificationChannel
  EmailNotificationChannel

core.service.processing
  OrderProcessor         submit, workers, shutdown
```

## Tasks

### TASK-F01 — Contextual exception types (`REQ-090`)

Add focused exceptions for inactive product, insufficient stock, duplicate
submission, and payment failure. Messages include IDs/context. Preserve cause
on translation.

Verification: `./mvnw -Dtest=InsufficientStockExceptionTest,InactiveProductExceptionTest,DuplicateOrderSubmissionExceptionTest,PaymentFailedExceptionTest test`

### TASK-F02 — Inventory reserve and compensate (`REQ-070`)

Atomic per-product decrement via `compute`. Multi-item reservations sort
product IDs and journal successful decrements. Partial failure releases exactly
those quantities. Concurrent reservations cannot oversell or go negative.

Verification: `./mvnw -Dtest=InventoryReservationTest test`

### TASK-F03 — Audit history (`REQ-110` overlap needed by fulfilment ACs)

Immutable events with unique ID, order ID, type, message, timestamp, thread
name. Concurrent record is safe. Queries immutable and ordered by timestamp
then event ID.

Verification: `./mvnw -Dtest=AuditLogTest test`

### TASK-F04 — Payment and notification boundaries (`REQ-080`)

Always-successful and configurable-failure payment. Email and console
channels. Notification failure is logged/audited and does not change order
state.

Verification: `./mvnw -Dtest=PaymentGatewayTest,NotificationChannelTest test`

### TASK-F05 — Order processor (`REQ-060`, `AC-040`, `AC-050`, `AC-060`, `AC-080`)

`BlockingQueue` + ≥3 workers + `ExecutorService`. Atomic duplicate-ID reject.
Worker: skip cancelled (audit no-op), `PROCESSING`, validate, price, reserve,
async payment, complete or compensate+fail, async notify. Isolate worker
failures. Graceful shutdown with documented timeout. Apply discount engine
during processing. Demonstrate `Predicate`/`Function`/`Consumer`/`Supplier`
and `java.util.logging` at this boundary. Record `createdAt` on Order.

Verification: `./mvnw -Dtest=OrderProcessorTest test`

### TASK-F06 — Fulfilment verify

`./mvnw clean verify` and record RED/GREEN evidence in `AI_USAGE.md`.
