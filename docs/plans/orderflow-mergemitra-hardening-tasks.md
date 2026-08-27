# OrderFlow Mergemitra Hardening Tasks

**Status:** Implemented — 2026-08-24
**Plan:** `orderflow-mergemitra-hardening-implementation-plan.md`

Every behavior task follows RED → GREEN → REFACTOR and records commands/results
in `AI_USAGE.md`.

## TASK-H01 — Domain timestamps and constructor contracts

**Traceability:** `REQ-030`, `REQ-130`, `AC-020`

Add focused constructor tests for `Order`, `OrderItem`, `OrderRequest`, and
`DiscountResult`. Inject a `Clock` through `OrderFactory`/`Order`; record
immutable `completedAt` only on successful completion. Split bundled invariant
tests into one behavior each.

**Files:** `Order.java`, `OrderFactory.java`, domain tests, pricing tests.

**RED/GREEN:**

```bash
./mvnw -Dtest=OrderTest,OrderFactoryTest,DiscountEngineTest test
```

## TASK-H02 — Reporting and audit correctness

**Traceability:** `REQ-100`, `REQ-110`, `REQ-130`, `AC-070`

Add fixed-clock cross-day reporting; implement largest-remainder cents with
exact nonnegative bucket assertions for the `$0.28` three-item and `$0.08`
ten-item examples; remove unused reporter state. Add mixed and arbitrarily long
same-timestamp audit IDs; implement a transitive overflow-free total comparator.
Split broad report tests by public behavior.

**Files:** `OrderReporter.java`, `CompletedOrdersPartition.java`,
`AuditLog.java`, `OrderReporterTest.java`, `AuditLogTest.java`.

**RED/GREEN:**

```bash
./mvnw -Dtest=OrderReporterTest,AuditLogTest test
```

## TASK-H03 — Canonicalization and public naming cleanup

**Traceability:** `REQ-020`, `REQ-040`, `REQ-080`, `REQ-120`, `AC-010`

Preserve email display case; strip Unicode surrounding separators, reject
embedded Unicode whitespace, and rename the normalized uniqueness accessor.
Remove dead request-list null branches. Rename `notify` to `deliver`, `passed`
to `isPassed`, the shutdown boolean accessor, and the completion partition API.
Keep one behavior per test and update all call sites.

**Files:** `Customer.java`, `CustomerDirectory.java`, validation rules/results,
notification types, demonstration/result, reporter, affected tests.

**Verification:**

```bash
./mvnw -Dtest=CustomerDirectoryTest,OrderValidationPipelineTest,NotificationChannelTest,OrderFlowDemonstrationTest test
```

## TASK-H04 — Processing policy and work tracking

**Traceability:** `REQ-060`, `REQ-090`, `AC-040`, `AC-080`

Test and add validated `OrderProcessingPolicy` defaults and injected test
values. Test and add `OrderWorkTracker` with unique begin/complete, bounded
await, and interrupt preservation. Add a contextual queue-capacity exception.

**Files:** new processing policy/tracker tests and production types; new focused
exception and contract test.

**RED/GREEN:**

```bash
./mvnw -Dtest=OrderProcessingPolicyTest,OrderWorkTrackerTest,OrderFlowExceptionContractTest test
```

## TASK-H05 — Bounded payment coordination

**Traceability:** `REQ-070`, `REQ-080`, `REQ-090`, `AC-050`, `AC-080`

Test success, gateway failure, timeout with interruption, bounded-queue
rejection, shutdown cancellation, and one outcome under timeout/success races.
Implement `PaymentCoordinator`, `PaymentOutcome`, and `ReservedOrderAttempt`
using a bounded executor, one scheduler, cancellable tasks, futures, and atomic
settlement.

**Files:** new processing coordinator/outcome/attempt types and tests.

**RED/GREEN:**

```bash
./mvnw -Dtest=PaymentCoordinatorTest,ReservedOrderAttemptTest test
```

## TASK-H06 — Bounded notification dispatch

**Traceability:** `REQ-080`, `AC-060`, `AC-080`

Test mixed channel success/failure, timeout with interruption, queue rejection,
shutdown cancellation, safe audit failure, and aggregate completion. Implement
the bounded, deadline-controlled `NotificationDispatcher`.

**Files:** new dispatcher and tests; notification types and audit integration.

**RED/GREEN:**

```bash
./mvnw -Dtest=NotificationDispatcherTest,NotificationChannelTest test
```

## TASK-H07 — Processor integration and shutdown

**Traceability:** `REQ-060`–`REQ-090`, `AC-040`–`AC-060`, `AC-080`

Integrate the policy, coordinators, attempt ownership, and work tracker into a
focused `OrderProcessor`. Add deterministic tests for:

- full ingress rejection before mutation and successful retry;
- submit-versus-shutdown overlap;
- three blocked payments timing out without starving a later order;
- payment-stage rejection with exact compensation;
- shutdown cancellation of queued and in-flight payment work;
- notification cancellation without final-state mutation;
- every accepted order final, no orphan reservation, idle complete, and every
  executor terminated;
- both PAYMENT and COMPLETED post-completion audit failures.

**Files:** `OrderProcessor.java`, processing collaborators/tests, demonstration.

**RED/GREEN:**

```bash
./mvnw -Dtest=OrderProcessorTest test
```

Repeat the focused concurrency suite three times after GREEN.

## TASK-H08 — Documentation, readiness, commit, and push

**Traceability:** `REQ-120`–`REQ-140`, `AC-080`, `AC-090`

Align README concurrency/shutdown/known-limitations text, diagrams, and
`AI_USAGE.md`. Run focused suites and full verification with the available JBR
targeting release 21. Inspect status and complete diff; exclude parent-repo,
IDE, generated, secret, and unrelated files. Copy only intended files to the
dedicated clone, commit, and push the feature branch to PR #1.

**Verification:**

```bash
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./mvnw clean verify
./mvnw -Dtest=OrderProcessorTest test
./mvnw -Dtest=OrderProcessorTest test
./mvnw -Dtest=OrderProcessorTest test
```
