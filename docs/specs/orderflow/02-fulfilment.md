# OrderFlow Fulfilment Contract

**Status:** Governed by `SPEC.md`
**Covers:** `REQ-060`–`REQ-090`, `AC-040`–`AC-060`, `AC-080`

Read `SPEC.md` first for assumptions, scope, architecture, source precedence,
and approval status.

## Requirements

### REQ-060: Process orders concurrently and at most once

A `BlockingQueue` shall feed at least three workers managed by an
`ExecutorService`. Submission shall atomically reject a duplicate order ID and
transition an accepted order from `CREATED` to `QUEUED`. A worker transitions it
to `PROCESSING`, validates, prices, reserves stock, invokes payment, then reaches
one final state. One failed order cannot terminate workers. Interrupts preserve
interrupt status where appropriate. All executors support graceful shutdown.
There is no thread-per-order, busy waiting, or uncontrolled infinite loop.

### REQ-070: Reserve and compensate inventory safely

Per-product quantity changes shall be atomic via `ConcurrentHashMap.compute`.
Multi-item reservations use deterministic product ordering and a reservation
journal. A failed partial reservation releases exactly the quantities already
decremented. Payment runs only after full reservation. Payment failure releases
the complete reservation and fails the order. Concurrent orders cannot oversell
or produce negative stock.

### REQ-080: Replace payment and notification implementations

`PaymentGateway` shall have an always-successful implementation and a
deterministic configurable failure implementation. `NotificationChannel` shall
have email and console implementations. Completion/failure notifications run
asynchronously and polymorphically. Notification failure is logged/audited and
cannot alter the final order state.

### REQ-090: Provide contextual exceptions

`OrderFlowException` shall be the base for focused exceptions covering invalid
product/customer/order/money, duplicates, missing/inactive entities, invalid
transitions, insufficient stock, and payment failure. Messages include useful
IDs/context. Translation preserves causes. Exceptions are caught only to
handle, translate, compensate, record, or isolate a failed order. Failed
operations leave valid state.

## Acceptance criteria

### AC-040: No overselling under contention

**Given** many concurrently submitted orders for one limited-stock product,
**when** workers process them from a common blocking queue, **then** completed
sold quantity never exceeds initial stock, stock never becomes negative,
duplicate IDs process at most once, and every accepted order reaches exactly one
final state.

### AC-050: Payment compensation

**Given** an order whose stock is fully reserved and whose configured payment
fails, **when** asynchronous payment completes, **then** the exact reservation is
released, the order becomes `FAILED`, audit captures payment failure and release,
and other orders continue processing.

### AC-060: Notification isolation

**Given** a final order and one failing notification channel, **when** channels
run asynchronously, **then** the failure is logged/audited, successful channels
can still run, and the final order state does not change.

### AC-080: Graceful shutdown

**Given** accepted orders and in-flight payment/notification work, **when**
shutdown is requested, **then** submissions stop, accepted work reaches a final
outcome within a documented timeout, executors terminate, and interrupt status
is handled correctly.

## Concurrency design decision

Alternatives considered:

1. `synchronized` around the complete inventory store — simplest correctness,
   but serializes unrelated products.
2. One `ReentrantLock` per product — explicit and supports compound sections,
   but adds lock lifecycle/order complexity.
3. `ConcurrentHashMap.compute` per product — selected for atomic per-key updates
   without managing lock objects. Multi-product atomicity is achieved by a
   deterministic reservation order plus a reservation journal and exact
   compensation on failure.

The selected approach favors the PoC's learning goals and safe independent
product concurrency. Validation is advisory; reservation is authoritative.

## Testing focus

- Deterministic contention with latches/barriers and bounded timeouts
- At-most-once submission and exactly one final state per accepted order
- No overselling or negative inventory
- Partial-reservation and payment-failure compensation
- Worker failure isolation, notification isolation, interrupt handling, and
  graceful executor shutdown
