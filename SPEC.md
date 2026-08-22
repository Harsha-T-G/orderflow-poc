# OrderFlow Product Specification

**Status:** Approved — 2026-08-21; brief closure and delivery closure ratified
2026-08-22 (Q1: Inventory read-through; Q2: docs/verify only, Git/PR deferred).
**Stack:** Plain Java 21, Maven, JUnit 5; in-memory only

## Assumptions

1. `com.codewalnut.orderflow` is the package root.
2. The application is a command-line proof of concept, not a reusable library or
   production service.
3. Product IDs, customer IDs, order IDs, event IDs, and timestamps may be
   generated or supplied through focused factories, but uniqueness must be
   enforced by the owning store.
4. Money is normalized to scale 2 using `RoundingMode.HALF_UP` at domain
   boundaries and after percentage calculations.
5. Discount thresholds use the original order amount and total requested item
   quantity; all eligible discounts stack, capped at 25%.
6. Validation is side-effect free. Inventory reservation is the authoritative
   stock decision because stock can change after validation.
7. Per-product inventory updates use `ConcurrentHashMap.compute`. Multi-product
   reservations process product IDs in deterministic order and compensate every
   prior decrement if a later item cannot be reserved.
8. Payment and notification use dedicated executors. The order workers do not
   create raw threads and graceful shutdown waits for tracked asynchronous work.
9. Notification failure is observable through logs and audit but never changes
   the order's final business state.
10. Java 21 is the release target even if a newer local JDK is temporarily used
    with `--release 21` during setup.

## Objective

Build a production-style, in-memory OrderFlow application that demonstrates
core Java, object-oriented design, collections, streams, functional interfaces,
exception design, concurrency, deterministic testing, documentation, and an
evidence-led agentic workflow. Multiple orders must be processed concurrently
without overselling inventory, corrupting state, or processing an accepted order
more than once.

## Scope

### In scope

- Product catalog and inventory operations
- Customer registration and lookup
- Order creation, item snapshots, totals, and status transitions
- Composable validation and discount rules
- Queue-based concurrent order processing
- Replaceable payment and notification boundaries
- Inventory reservation and payment-failure compensation
- Immutable concurrent audit history
- Stream-based business reports
- Command-line demonstration data and graceful shutdown
- JUnit unit, behavior, and deterministic concurrency tests
- Agent guidance, AI usage record, diagrams, and PR evidence

### Out of scope

- Spring Boot or any dependency-injection framework
- Database, files as persistence, web API, UI, or network transport
- Authentication, authorization, multi-tenancy, currencies, tax, shipping, or
  production payment/email integrations
- Lombok, ORM, reactive frameworks, and external logging frameworks
- Distributed transactions, multi-process coordination, or production-grade
  durability/recovery

## Architecture

Use `core.domain` for models and `core.service` for operations under
`com.codewalnut.orderflow`:

```text
core.domain.catalog     Product, ProductStatus
core.domain.customer    Customer, CustomerType
core.domain.order       Order, OrderItem, OrderRequest, OrderStatus
core.domain.pricing     DiscountContext, DiscountRule, DiscountResult
core.domain.inventory   Reservation
core.domain.audit       AuditEvent, AuditEventType
core.service.catalog    ProductCatalog
core.service.customer   CustomerDirectory
core.service.inventory  Inventory
core.service.order      OrderFactory, validation pipeline
core.service.pricing    DiscountEngine
core.service.audit      AuditLog
core.service.payment    PaymentGateway implementations
core.service.notification NotificationChannel implementations
core.service.processing OrderProcessor
core.service.reporting  OrderReporter
core.exception          OrderFlowException hierarchy
```

Fulfilment capabilities are implemented as the matching domain and service
folders above. Do not create `web`, `mapper`, `gateway.http`, or `config`
packages; this PoC has no HTTP API or Spring.

Interfaces are required only at genuine substitution boundaries: validation
rules, discount rules, payment gateway, and notification channels. Repositories
remain concrete in-memory components unless a second implementation is needed.

## Contract map

`SPEC.md` is the canonical approval and index document. Its status governs every
linked contract chunk. Read only the chunk needed for the active task, plus this
file and `CONTEXT.md`.

| Contract chunk | Requirements | Acceptance criteria |
| --- | --- | --- |
| [Core domain](docs/specs/orderflow/01-core-domain.md) | `REQ-010`–`REQ-050` | `AC-010`–`AC-030` |
| [Fulfilment](docs/specs/orderflow/02-fulfilment.md) | `REQ-060`–`REQ-090` | `AC-040`–`AC-060`, `AC-080` |
| [Reporting and quality](docs/specs/orderflow/03-reporting-quality.md) | `REQ-100`–`REQ-140` | `AC-070`, `AC-090` |
| [Brief closure](docs/specs/orderflow/04-brief-closure.md) | `REQ-150`–`REQ-180` | `AC-100`–`AC-120` |
| [Delivery closure](docs/specs/orderflow/05-delivery-closure.md) | `REQ-190`–`REQ-220` | `AC-130`–`AC-150` |

### REQ-001: Enforce project constraints

The build shall target Java 21 and use Maven and JUnit 5. Production code shall
use only the JDK. The system shall remain in memory and shall not use Spring,
Lombok, a database, or a web API.

### AC-001: Java 21 build boundary

**Given** a Java 21 JDK and clean checkout, **when** `./mvnw clean verify` runs,
**then** production and test sources compile for release 21 and all tests pass
without Spring, database, Lombok, or web dependencies.

## Critical business invariants

- IDs are immutable and unique within their owning store.
- Money uses `BigDecimal`, scale 2, and `RoundingMode.HALF_UP`.
- Internal mutable collections are never exposed.
- Order item snapshots and completed financial values do not change.
- Unsupported status transitions leave the order unchanged.
- Validation is side-effect free; reservation is the authoritative stock check.
- Concurrent processing cannot oversell stock or process one accepted order
  more than once.
- Failed partial reservation or payment releases exactly what was reserved.
- Notification failure cannot alter a final order state.
- Every accepted order reaches at most one final state.

## Acceptance-criteria index

- `AC-001` — Java 21 build boundary
- `AC-010` — product and customer invariants
- `AC-020` — order snapshots and transitions
- `AC-030` — discount behavior and cap
- `AC-040` — no overselling under contention
- `AC-050` — exact payment-failure compensation
- `AC-060` — notification failure isolation
- `AC-070` — immutable, correct reporting
- `AC-080` — graceful executor shutdown
- `AC-090` — evidence-led delivery
- `AC-100` — quantity read-through and inactive product
- `AC-110` — async futures, created audit, and quantity ranking
- `AC-120` — tests and README closure
- `AC-130` — ratified contract matches tests
- `AC-140` — diagrams and docs match the tree
- `AC-150` — delivery evidence exists or is explicitly deferred

## Approved decisions

1. A cancelled queued order remains in the queue. A worker may dequeue it,
   record an audit no-op, and skip processing.
2. Invalid creation produces no order. A failure after the order enters
   `PROCESSING` produces a `FAILED` order.
3. Email uniqueness is case-insensitive using normalization with `Locale.ROOT`;
   the display form may be retained separately.
4. CLI presentation is not contractual. Report values and lifecycle behavior
   are contractual.
5. Available quantity is owned by Inventory. `ProductCatalog.availableQuantity`
   is a read-through. `Product` has no stored or settable quantity field.
6. Git history and a pull request are deferred until the user explicitly asks
   to commit or open a PR (delivery-closure Q2 = C, 2026-08-22).

## Open questions

None. Brief-closure Q1 and delivery-closure Q2 were answered 2026-08-22.
