# OrderFlow Brief-Closure Contract

**Status:** Approved — 2026-08-22 (ratified with delivery closure; Q1 yes).
**Covers:** `REQ-150`–`REQ-180`, `AC-100`–`AC-120`
**Source:** remaining items in `docs/requirements/orderflow-poc-brief.txt`
after the Core Domain, Fulfilment, and Reporting implementations.

Read `SPEC.md` and `CONTEXT.md` first. This chunk only covers gaps called out
in the 2026-08-21 brief review. It does not re-open overselling, compensation,
or discount-cap behavior.

## Assumptions

1. Inventory remains the only mutable stock store. Callers still cannot set
   stock on `Product`. The brief’s Product “available quantity” item is met by
   a **read-through** from Inventory, not a second stored quantity field.
2. Existing dedicated payment and notification executors stay. Asynchronous
   work is submitted with `CompletableFuture` **on those executors**, not with
   raw threads or a hidden default `ForkJoinPool` for business work.
3. Collection-API demonstrations (`computeIfAbsent`, `computeIfPresent` or
   `compute`, `merge`, `getOrDefault`, `Set.add`) must appear in real production
   paths, not unused examples.
4. `InactiveProductException` must be thrown for inactive-product order
   attempts, not only declared.
5. When a caught exception is translated to `OrderFlowException`, the original
   cause is preserved and tested.
6. Order creation records a `CREATED` audit event. Factory construction stays
   the creation boundary; invalid creation still produces no order and no
   created audit.
7. Top five products are ranked by **completed sold quantity**, then product ID.
8. Git commits and pull requests happen only after an explicit human request.
9. `CLAUDE.md` is out of scope unless Claude Code is used.
10. Native JDK 21 verification is an environment evidence item, not a product
    behavior change.

## Open question

None. Q1 was answered 2026-08-22: Inventory-owned quantity with catalog
read-through; `Product` has no stored quantity field.

## Requirements

### REQ-150: Expose product quantity without duplicating stock

The catalog shall answer available quantity for a product ID from Inventory.
`Product` must not store or accept a settable quantity. External code still
must not set stock except through Inventory/catalog stock operations.
Catalog sorts and low-stock queries continue to use Inventory quantity.

### REQ-160: Close remaining brief APIs and exception behavior

- Payment and notification work shall be submitted with `CompletableFuture`
  bound to the existing dedicated executors. Shutdown still waits for that
  work. Interrupt status remains preserved.
- Production code shall use `computeIfAbsent`, `getOrDefault`, and either
  `computeIfPresent` or the existing `compute` in live paths, in addition to
  current `merge`, `compute`, and `Set.add` duplicate rejection.
- Ordering or processing an inactive product shall throw
  `InactiveProductException` with the product ID in the message. Other
  validation failures remain `InvalidOrderException` (or the current named
  pipeline result at validation-only calls).
- Translating a failure into `PaymentFailedException` or another
  `OrderFlowException` shall pass the original cause. A test must assert
  `getCause()`.
- Successful `OrderFactory.create` shall record an immutable `CREATED` audit
  event for that order ID.

### REQ-170: Rank top products by sold quantity and show audit events

- The top-five-products report shall rank by completed sold quantity
  descending, then product ID. Revenue may be included on the result but must
  not be the sort key.
- The demonstration shall print audit events (id, order id, type, message,
  timestamp, thread), not only an event count, along with summaries,
  inventory, and all reports.

### REQ-180: Close test and documentation evidence

JUnit 5 tests shall cover: each required custom exception type and useful
message context; cause preservation on translation; inactive-product creation;
top-five ranking by quantity; created audit on successful create and no created
audit on rejected create. The existing limited-stock contention test shall be
repeatable with `@RepeatedTest` (or equivalent) while still using
latches/barriers, not sleep-only coordination.

`README.md` shall add: JVM notes for this design (stack vs heap, shared
mutable inventory/order state, visibility via concurrent collections and
`synchronized` order status, atomicity of `compute`, happens-before on
executor handoff); known limitations; possible improvements. Collection
choices already listed in README stay and must mention the newly demonstrated
APIs.

Git history and a pull request remain `REQ-140` / `AC-090` evidence and are
**not executed** until the user explicitly asks to commit or open a PR.

## Acceptance criteria

### AC-100: Quantity read-through and inactive product

**Given** a registered product with inventory 7,
**when** catalog available-quantity is read,
**then** the value is 7 and `Product` has no settable quantity field.

**Given** an inactive product and a valid customer,
**when** an order is created for that product,
**then** `InactiveProductException` is thrown, no `Order` is created, and no
`CREATED` audit event exists for that order ID.

### AC-110: Async futures, created audit, and quantity ranking

**Given** a successful order create then submit,
**when** processing completes,
**then** audit contains `CREATED` then later lifecycle events in timestamp
order, payment/notification work used `CompletableFuture`, and a payment
failure still releases the exact reservation.

**Given** completed orders with different sold quantities,
**when** top five products are reported,
**then** ranking is by quantity then product ID, and the result is immutable.

### AC-120: Tests and README closure

**Given** the closure tests and README,
**when** `./mvnw clean verify` runs,
**then** exception, cause, created-audit, inactive-product, ranking, and
repeated contention tests pass, and README includes JVM concurrency notes,
limitations, and improvements. Commit/PR are absent unless explicitly
requested.

## Out of scope

- Spring, persistence, web API, extra dependencies
- Storing quantity on `Product`
- Changing the `ConcurrentHashMap.compute` reservation strategy
- `CLAUDE.md`
- Opening a pull request or creating git commits without an explicit request
