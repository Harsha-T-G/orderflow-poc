# OrderFlow

OrderFlow is a plain-Java 21 proof of concept for concurrent in-memory order,
inventory, payment, fulfilment, audit, and reporting workflows.

**Status:** Core Domain, Fulfilment, Reporting, and brief-closure behavior are
implemented and ratified. Native JDK 21 verification remains pending if only
JetBrains Runtime is available locally. Git history and a pull request wait on
an explicit request.

Available quantity is owned by Inventory. The catalog reads it through;
`Product` has no quantity field.

## Prerequisites

- JDK 21
- Maven Wrapper (checked in)

## Commands

```bash
./mvnw clean verify
./mvnw -Dtest=ClassName test
java -cp target/classes com.codewalnut.orderflow.OrderFlowApplication
```

If `java` is not on `PATH`, use a JDK 21 `JAVA_HOME` (IntelliJ's bundled JBR
can compile with `--release 21`).

## Project guidance

- Product contract: `SPEC.md`
- Capability contracts: `docs/specs/orderflow/`
- Vocabulary and source ranking: `CONTEXT.md`
- Agent instructions: `AGENTS.md`
- Java conventions: `.guidelines/java.md`
- Design: `docs/superpowers/specs/2026-08-21-orderflow-design.md`
- Implementation plans: `docs/plans/`
- Diagrams: `docs/diagrams/`
- AI decision log: `AI_USAGE.md`

## Architecture

```text
com.codewalnut.orderflow
├── OrderFlowApplication            CLI bootstrap only
├── OrderFlowDemonstration          seeded run, wait, print, shutdown
└── core
    ├── domain                      models and immutable results
    ├── service                     catalog, inventory, orders, processing, reports
    └── exception                   OrderFlowException hierarchy
```

Substitution boundaries are interfaces: validation rules, discount rules,
`PaymentGateway`, and `NotificationChannel`.

## Collections

- `Map` for ID lookup; `ConcurrentHashMap` for inventory and submitted IDs
- `Set.add` on `ConcurrentHashMap.newKeySet()` rejects duplicate submissions
- `BlockingQueue` (`LinkedBlockingQueue`) feeds workers
- `ConcurrentHashMap.compute` atomically changes per-product stock
- `putIfAbsent` registers inventory; `computeIfAbsent` stores accepted orders
- `getOrDefault` aggregates duplicate requested quantities; `merge` combines
  reservation line quantities
- Query methods return `List.copyOf` / `Map.copyOf` / stream `toList()` snapshots

## Exceptions

`OrderFlowException` is the base. Focused subclasses include invalid
product/customer/order/money, duplicates, missing/inactive entities, invalid
transitions, insufficient stock, and payment failure. Messages include IDs.
Causes are preserved when translating. Failed operations leave valid state.

## Streams and functional types

`OrderReporter` uses streams/collectors (no loops) for the twelve required
reports. Validation and discounts are composable functions.
`OrderProcessor` uses `Predicate`, `Function`, `Consumer`, and `Supplier`
at the processing boundary. Services log with `java.util.logging`.

## Concurrency

At least three `ExecutorService` workers drain a shared blocking queue.
Payment and notification run as `CompletableFuture` tasks on dedicated
executors. Duplicate order IDs are rejected atomically. One failed order
cannot stop workers. Interrupts restore interrupt status. There is no
thread-per-order, busy waiting, or `Thread.stop`.

## JVM concurrency notes

Workers keep method-local state on their stacks (the current `Order`,
reservation journal copy, pricing result). Shared heap state is the inventory
map, submitted-ID set, order map, queue, and audit log. Inventory updates are
atomic per product through `ConcurrentHashMap.compute`. Order status mutations
are `synchronized` on the order instance, which creates a happens-before
relationship for later synchronized reads. Handing work to an
`ExecutorService` / `CompletableFuture` also establishes happens-before from
the submitting thread to the worker. A concurrent collection is not enough
for multi-item reservation; that uses a journal plus exact compensation.

## Compensation and shutdown

Multi-item reservations sort product IDs, journal successful decrements, and
release exactly those quantities on later failure. Payment runs only after a
full reservation. Payment failure releases the reservation and fails the
order. Notification failure is audited/logged and cannot change a final
status. `shutdown()` stops submissions and waits up to 10 seconds per executor.

## Demonstration

`OrderFlowApplication` boots `OrderFlowDemonstration`, which seeds at least 15
products in 4 categories, 10 customers of every type, and 50 order attempts
including invalid data, stock contention, and payment failure. It submits
concurrently, waits with `awaitIdle` (not a sleep), prints summaries,
inventory, audit events, and reports, then shuts down every executor.

## Known limitations and possible improvements

- In-memory only: restart loses all state; there is no durability or crash
  recovery.
- Payment and email are simulated; they do not call external systems.
- Native JDK 21 verification may still be pending if the local machine uses
  IntelliJ JBR with `--release 21`.
- Possible improvements: persist audit/orders, split multi-warehouse stock,
  add a real payment adapter behind `PaymentGateway`, and expose metrics for
  queue depth and reservation contention.
