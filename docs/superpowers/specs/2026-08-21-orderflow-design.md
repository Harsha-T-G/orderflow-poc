# OrderFlow Design

**Status:** Approved in conversation on 2026-08-21; product `SPEC.md` approved
on 2026-08-21.

## Goal and boundaries

Build a two-day, plain-Java 21 in-memory PoC that demonstrates object-oriented
design, collections, functional rules, streams, exceptions, concurrency,
testing, documentation, and controlled AI-assisted delivery. No Spring,
database, web API, Lombok, or external application framework.

## Chosen architecture

Use `core.domain` for models and `core.service` for operations under
`com.codewalnut.orderflow`. Keep concrete in-memory components unless a real
substitution boundary exists. Interfaces are appropriate for validation rules,
discount rules, payment, and notification. Do not add empty `web`, `mapper`,
`gateway`, or `config` packages. The CLI composes components; it does not
contain business logic.

Inventory uses `ConcurrentHashMap.compute` for atomic per-product changes.
Multi-product reservation sorts product IDs, records each successful decrement,
and compensates that journal if a later decrement fails. This avoids overselling
without a global inventory lock. A transient reservation may make another order
fail availability; correctness is preferred over retry/fairness in this PoC.

Orders own synchronized/atomic status transitions. The submission ID set is
thread-safe. A `BlockingQueue` feeds a fixed worker pool. Dedicated executors run
payment and notification `CompletableFuture` work; in-flight futures are tracked
for bounded graceful shutdown.

## Components

- Catalog and Customer: unique maps/indexes and immutable query results.
- Order: aggregate, item snapshots, valid transitions, in-memory store.
- Validation/Pricing: ordered composable rules and immutable results.
- Inventory: atomic reserve/release and reservation journal.
- Processing: at-most-once submission, workers, orchestration, lifecycle.
- Payment/Notification: replaceable deterministic boundaries.
- Audit: concurrent immutable event storage and sorted snapshots.
- Reporting: immutable stream/collector results.

## Data flow

```mermaid
sequenceDiagram
    participant CLI
    participant Submit as OrderSubmissionService
    participant Queue as BlockingQueue
    participant Worker as OrderWorker
    participant Validate as ValidationPipeline
    participant Inventory
    participant Payment
    participant Notify as NotificationDispatcher
    participant Audit

    CLI->>Submit: submit(orderId)
    Submit->>Queue: enqueue once
    Submit->>Audit: ORDER_QUEUED
    Worker->>Queue: take()
    Worker->>Validate: evaluate(order)
    Worker->>Inventory: reserveAll(items)
    Inventory-->>Worker: reservation journal
    Worker->>Payment: process asynchronously
    alt payment succeeds
        Payment-->>Worker: success
        Worker->>Audit: ORDER_COMPLETED
        Worker->>Notify: notify asynchronously
    else payment fails
        Payment-->>Worker: failure
        Worker->>Inventory: release(journal)
        Worker->>Audit: PAYMENT_FAILED + INVENTORY_RELEASED + ORDER_FAILED
        Worker->>Notify: notify asynchronously
    end
```

## Error handling

Reject invalid input before storage mutation. Reservation failure compensates
partial decrements. Payment failure compensates the complete reservation.
Notification failure is isolated after final state. Worker boundaries catch and
record one order's failure without terminating the pool. Translated exceptions
retain causes and contextual IDs.

## Testing

Use JUnit 5 and RED/GREEN/REFACTOR. Unit-test invariants and rules; component-test
real in-memory stores; use deterministic fakes for payment/notifications;
coordinate concurrency with latches/barriers/futures and bounded timeouts.
Verify no overselling, at-most-once processing, exact compensation, failure
isolation, and clean shutdown. The full gate is `./mvnw clean verify` on JDK 21.

## Agentic workflow

Initial setup creates guidance, glossary, draft product spec, reusable skills,
Java rules, AI usage log, and PR template. No CI is added. After the human
approves `SPEC.md`, create and approve an implementation plan; then create and
approve traceable tasks; only then implement each task test-first and verify it.

## Specification layout

Use progressive disclosure rather than one oversized contract. `SPEC.md`
remains the canonical status, assumptions, scope, architecture, invariant, and
contract-index document. Detailed requirements, acceptance criteria, and
testing focus live in three capability chunks:

- `docs/specs/orderflow/01-core-domain.md`
- `docs/specs/orderflow/02-fulfilment.md`
- `docs/specs/orderflow/03-reporting-quality.md`

Agents read `SPEC.md`, `CONTEXT.md`, and only the chunk routed for the active
task. Requirement and acceptance-criteria IDs remain globally unique.
