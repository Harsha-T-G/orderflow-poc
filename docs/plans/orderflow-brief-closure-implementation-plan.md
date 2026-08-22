# OrderFlow Brief-Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use repository
> `test-driven-development`. Every behavior follows RED → GREEN → REFACTOR.
> Do not implement until `docs/specs/orderflow/04-brief-closure.md` is
> approved. Do not commit unless the user explicitly requests a commit.

**Status:** Implemented — 2026-08-22 (user requested Day 2
implementation; Q1 used the recommended Inventory read-through)

**Goal:** Close remaining original-brief gaps (`REQ-150`–`REQ-180`,
`AC-100`–`AC-120`) without reopening approved fulfilment invariants.

**Architecture:** Inventory stays the stock owner; catalog reads quantity
through. `OrderFactory` records `CREATED` via `AuditLog`. Inactive-product
failures become `InactiveProductException`. Payment/notification tasks use
`CompletableFuture` on the existing dedicated executors. Top-five products
sort by sold quantity. README/docs close evidence; git/PR stay gated.

**Tech stack:** Java 21, Maven Wrapper, JUnit 5, JDK `CompletableFuture` and
collections only.

## Global constraints

- Read `SPEC.md`, `CONTEXT.md`, and
  `docs/specs/orderflow/04-brief-closure.md` before each task.
- Do not store quantity on `Product` or add Spring/Lombok/dependencies.
- Do not change `ConcurrentHashMap.compute` reservation strategy.
- Tests: Given-When-Then names; `// Arrange` `// Act` `// Assert`.
- Do not commit or open a PR unless the user explicitly asks.

## Planned files

```text
src/main/java/.../core/service/catalog/ProductCatalog.java
  + availableQuantity(productId) read-through if missing
src/main/java/.../core/service/order/OrderFactory.java
  + AuditLog; CREATED event; InactiveProductException mapping
src/main/java/.../core/service/order/validation/OrderValidationRule.java
  (only if mapping is cleaner at the factory)
src/main/java/.../core/service/processing/OrderProcessor.java
  CompletableFuture on payment/notification executors
src/main/java/.../core/service/inventory/Inventory.java
  or directory/processor: live computeIfAbsent / getOrDefault
src/main/java/.../core/service/reporting/OrderReporter.java
  top five by quantity then product ID
src/main/java/.../OrderFlowDemonstration.java
  print full audit events
src/main/java/.../core/exception/PaymentFailedException.java
  already has cause constructor; use it on translation
src/test/java/... matching tests including @RepeatedTest
README.md
```

## Risks

- `CompletableFuture.runAsync(task)` without an executor uses ForkJoinPool;
  always pass `paymentExecutor` / `notificationExecutor`.
- Creating `AuditLog` in every existing factory test; keep a test helper or
  optional no-op is forbidden — inject a real `AuditLog`.
- Ranking change can break `OrderReporterTest` that currently expects
  revenue order.

## Tasks

### TASK-C01 — Quantity read-through (`REQ-150`, `AC-100`)

If `ProductCatalog` does not already expose `availableQuantity(productId)`,
add it as a pass-through to Inventory. Test that the value matches Inventory
and that `Product` has no quantity setter/field.

Verification: `./mvnw -Dtest=ProductCatalogTest test`

### TASK-C02 — InactiveProductException on create (`REQ-160`, `AC-100`)

Failing test: inactive product → `InactiveProductException`, no order, no
`CREATED` audit. Map the active-products validation failure in
`OrderFactory` (do not weaken the pipeline). Other failures stay
`InvalidOrderException`.

Verification: `./mvnw -Dtest=OrderFactoryTest test`

### TASK-C03 — CREATED audit (`REQ-160`, `AC-110`)

`OrderFactory` takes `AuditLog`. Successful create records `CREATED`.
Rejected create records nothing. Update factory call sites (processor tests,
demonstration, factory tests).

Verification: `./mvnw -Dtest=OrderFactoryTest,AuditLogTest test`

### TASK-C04 — Cause-preserving translation (`REQ-160`, `AC-120`)

When wrapping a non-domain payment failure, throw
`PaymentFailedException(orderId, cause)` or equivalent. Test `getCause()`.

Verification: `./mvnw -Dtest=PaymentGatewayTest,OrderProcessorTest test`

### TASK-C05 — CompletableFuture on dedicated executors (`REQ-160`, `AC-110`)

Replace `paymentExecutor.execute` / `notificationExecutor.execute` with
`CompletableFuture.runAsync(..., executor)`. Shutdown still waits. Keep
isolation and compensation tests green.

Verification: `./mvnw -Dtest=OrderProcessorTest test`

### TASK-C06 — Collection APIs in live paths (`REQ-160`)

Use `getOrDefault` and `computeIfAbsent` where they replace equivalent
get/put logic (for example submitted-order index or quantity aggregation).
Keep `compute` / `merge` / `Set.add`. Mention them in README in TASK-C09.

Verification: `./mvnw -Dtest=InventoryReservationTest,OrderProcessorTest test`

### TASK-C07 — Top five products by quantity (`REQ-170`, `AC-110`)

Change sort to sold quantity desc, then product ID. Update
`OrderReporterTest`.

Verification: `./mvnw -Dtest=OrderReporterTest test`

### TASK-C08 — Demo audit listing (`REQ-170`)

Print each audit event’s fields. Test output contains type/message, not
only `"Audit events: N"`.

Verification: `./mvnw -Dtest=OrderFlowDemonstrationTest test`

### TASK-C09 — Exception coverage, repeated contention, README (`REQ-180`, `AC-120`)

- Tests for each required exception type and message context
- `@RepeatedTest` on limited-stock contention
- README: JVM stack/heap, visibility, atomicity, happens-before; limitations;
  improvements; collection APIs from TASK-C06

Verification: `./mvnw clean verify`

### TASK-C10 — Git / PR (gated)

Do nothing unless the user explicitly asks to commit or open a PR.
`REQ-140` remains outstanding until then.

## Verification checkpoint

After TASK-C09: `./mvnw clean verify` (expect existing suite plus new tests,
zero failures). Record commands in `AI_USAGE.md`. Native JDK 21 remains an
environment checkbox, not a code task.
