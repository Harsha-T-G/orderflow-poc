# OrderFlow Reporting and Quality Contract

**Status:** Governed by `SPEC.md`
**Covers:** `REQ-100`–`REQ-140`, `AC-070`, `AC-090`

Read `SPEC.md` first for assumptions, scope, architecture, source precedence,
and approval status.

## Requirements

### REQ-100: Produce immutable stream reports

The reporting component shall use streams/collectors, not loops, for: completed
revenue; revenue by category; orders by status; spending by customer; top five
customers; top five products; average completed order value; completed orders
by day; failures by reason; low stock sorted by quantity then name; unique tags
alphabetically; and highest-value completed order by customer type. It shall
demonstrate filter, map, flatMap, sorted, distinct, reduce, collect, groupingBy,
partitioningBy, mapping, counting, reduction/summing, and maxBy where suitable.
Empty input returns non-null immutable results. Failed/cancelled orders do not
contribute to completed revenue.

### REQ-110: Maintain safe audit history

Immutable events shall record creation, queueing, processing, validation,
reservation, payment, release, final status, and notification outcome. Each
event includes unique ID, order ID, type, message, timestamp, and thread name.
Concurrent recording must be safe. Query results are immutable and sorted by
timestamp with event ID as a deterministic tie-breaker.

### REQ-120: Demonstrate the complete workflow

The runner shall create at least 15 products in four categories, 10 customers
across all types, and 50 orders including contention, invalid data, insufficient
stock, and payment failure. It submits concurrently, waits without arbitrary
sleep, displays order summaries, inventory, audit, and all reports, then shuts
down every executor. Business logic does not live in `main`.

### REQ-130: Verify behavior deterministically

JUnit 5 tests shall independently cover product/customer validation and
uniqueness; order creation/snapshots/transitions; each discount and cap;
exception context and atomic failure; every report and empty input; duplicate
submission; payment compensation; worker failure isolation; shutdown; and
inventory contention. Concurrency tests shall coordinate with latches/barriers
or equivalent, not depend solely on `Thread.sleep`, and critical tests shall be
safe to repeat.

### REQ-140: Preserve engineering evidence

The repository shall contain `SPEC.md`, `README.md`, `AGENTS.md`, `CONTEXT.md`,
`AI_USAGE.md`, class and sequence diagrams, meaningful Git history, and a PR
summary with assumptions, decisions, and exact verification evidence. Generated
code is reviewed; accepted and rejected agent suggestions are recorded; the
final diff is checked for unrelated files and secrets.

## Acceptance criteria

### AC-070: Reporting correctness

**Given** completed, failed, and cancelled orders plus product/customer data,
**when** all required reports run, **then** grouping, ranking, ordering, empty
behavior, and BigDecimal totals match the source state and results are immutable.

### AC-090: Evidence-led delivery

**Given** the final change set, **when** readiness verification runs, **then**
requirements trace to tests, exact commands/outcomes are captured, diagrams and
docs match behavior, no unrelated/generated/secret files are included, and the
solution can be explained without the coding agent.

## Testing strategy

- Domain/unit tests for constructors, value objects, transitions, rules, money,
  and exception contracts.
- Component tests for catalog, customers, inventory, audit, and reports using
  real in-memory implementations.
- Processing tests with deterministic payment/notification fakes.
- Concurrency tests coordinated by `CountDownLatch`, `CyclicBarrier`, or
  equivalent; no timing-only assertions.
- Full gate: `./mvnw clean verify`.

## Success criteria

The PoC is complete only when all requirements and acceptance criteria are
implemented and verified, contention cannot oversell, payment compensation is
exact, duplicate submissions are at-most-once, all executors shut down, reports
are correct/immutable, documentation matches the tree, and the author can
explain the important design decisions.
