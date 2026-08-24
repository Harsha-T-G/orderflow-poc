# OrderFlow Mergemitra Hardening Implementation Plan

**Status:** Implemented — 2026-08-24
**Contract:** Approved 2026-08-23 amendment to `SPEC.md`
**Requirements:** `REQ-020`, `REQ-030`, `REQ-060`–`REQ-110`, `REQ-130`
**Acceptance criteria:** `AC-010`, `AC-020`, `AC-040`–`AC-080`

## Goal

Close every technically valid unresolved Mergemitra finding without adding a
dependency or weakening the approved in-memory Java 21 boundary. Preserve exact
inventory compensation, at-most-once processing, immutable results, and
case-preserving customer display email.

## Component design

### Processing policy and bounded stages

Add an immutable `OrderProcessingPolicy` for order/payment/notification worker
counts, queue capacities, payment and notification deadlines, and one global
shutdown budget. Defaults are the approved 3/256/5s/2s/10s values; tests inject
small positive policies.

Use explicit bounded `ThreadPoolExecutor` instances with `AbortPolicy`.
`OrderProcessor.submit` checks ingress capacity and lifecycle under its existing
lock, rejects before mutation when full, and uses nonblocking `offer`.

### Payment coordination

Extract `PaymentCoordinator`. It executes a cancellable charge task in a bounded
pool, schedules its deadline on one managed scheduler, and returns a
`CompletableFuture<PaymentOutcome>`. Success, gateway failure, timeout,
executor rejection, and shutdown cancellation complete that future once.

`OrderProcessor` owns a `ReservedOrderAttempt` for each reservation. Its atomic
settlement gate ensures only one payment outcome can either retain stock for a
completed order or release it for a failed order.

### Notification coordination

Extract `NotificationDispatcher`. Each channel delivery is cancellable,
bounded, and deadline-controlled. It records delivery outcomes safely and
returns one aggregate `CompletableFuture<Void>`. Failure, timeout, rejection,
or shutdown cancellation cannot mutate the final order state.

### Work and shutdown lifecycle

Extract `OrderWorkTracker` to register accepted order IDs and complete each ID
once. `awaitIdle` delegates to this tracker.

Shutdown:

1. Stop submissions under the lifecycle lock and start one deadline.
2. Let order workers drain; cancel any unstarted queued orders if the deadline
   is exhausted.
3. Cancel remaining payment attempts, causing exact compensation and failure.
4. Keep notification dispatch open while payment outcomes finalize, then cancel
   remaining deliveries.
5. Close work tracking and terminate every managed executor within the remaining
   cooperative-adapter budget.

### Domain, reporting, and audit corrections

- Inject `Clock` through `OrderFactory` into `Order`; `complete` records one
  immutable `completedAt`.
- Replace revenue allocation with deterministic largest-remainder cents.
- Replace the audit event-ID parser with an overflow-free total comparator.
- Preserve email display casing while stripping/rejecting Unicode spaces.
- Remove dead request-list null branches.

### Public naming cleanups

- `NotificationChannel.notify` → `deliver`
- `ValidationResult.passed` → `isPassed`
- `DemonstrationResult.processorShutdown` → `isProcessorShutdown`
- `completedVersusOther` boolean map → immutable `CompletedOrdersPartition`
- Remove the unused `Order` field from reporter `OrderLine`

## Main files

- `src/main/java/com/codewalnut/orderflow/core/service/processing/OrderProcessor.java`
- `src/main/java/com/codewalnut/orderflow/core/service/processing/OrderProcessingPolicy.java`
- `src/main/java/com/codewalnut/orderflow/core/service/processing/PaymentCoordinator.java`
- `src/main/java/com/codewalnut/orderflow/core/service/processing/PaymentOutcome.java`
- `src/main/java/com/codewalnut/orderflow/core/service/processing/NotificationDispatcher.java`
- `src/main/java/com/codewalnut/orderflow/core/service/processing/OrderWorkTracker.java`
- `src/main/java/com/codewalnut/orderflow/core/service/processing/ReservedOrderAttempt.java`
- `src/main/java/com/codewalnut/orderflow/core/domain/order/Order.java`
- `src/main/java/com/codewalnut/orderflow/core/service/order/OrderFactory.java`
- `src/main/java/com/codewalnut/orderflow/core/service/reporting/OrderReporter.java`
- `src/main/java/com/codewalnut/orderflow/core/service/audit/AuditLog.java`
- `src/main/java/com/codewalnut/orderflow/core/domain/customer/Customer.java`
- affected interfaces, CLI records, demonstrations, tests, README, and evidence

## Dependency order

1. Domain clocks, invariant tests, request/email cleanup, and API renames.
2. Reporting allocation and audit ordering.
3. Processing policy, work tracker, payment coordinator, notification
   dispatcher, and focused component tests.
4. `OrderProcessor` integration and deterministic concurrency/shutdown tests.
5. CLI, README, diagrams, evidence, full verification, commit, and push.

## Risks and controls

- Payment timeout can race a late success: `ReservedOrderAttempt` provides the
  single settlement decision; late outcomes are ignored.
- Executor rejection can happen after reservation: the failed payment outcome
  immediately compensates through the same settlement path.
- Notification cancellation can race completion: per-delivery futures and the
  aggregate future complete once; order state is already final.
- A non-cooperative adapter can retain a JVM thread: this violates the approved
  adapter contract and is logged; unsafe thread termination is not attempted.
- Largest-remainder ties can otherwise vary: immutable item order is the final
  deterministic tie-breaker.

## Verification checkpoints

Run focused RED/GREEN commands from the task list, then:

```bash
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./mvnw clean verify
./mvnw -Dtest=OrderProcessorTest test
./mvnw -Dtest=OrderProcessorTest test
./mvnw -Dtest=OrderProcessorTest test
```

Inspect the complete diff, copy only intended files to the dedicated
`/Users/harsh01/orderflow-poc` clone, commit on
`feature/orderflow-implementation`, and push to the existing PR.
