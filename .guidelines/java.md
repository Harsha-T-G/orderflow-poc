# OrderFlow Java Guidelines

## Baseline

- Target Java 21 and plain JDK APIs. Do not add Spring, Lombok, persistence,
  networking, or logging dependencies without approval.
- Package root: `com.codewalnut.orderflow`. Put models in `core.domain`,
  operations in `core.service`, and errors in `core.exception`. Do not add
  empty web, mapper, gateway, or config packages.
- Prefer composition. Use inheritance only for the exception hierarchy or a
  genuine is-a relationship.
- Introduce interfaces only at real substitution boundaries.

## Domain design

- Prevent invalid state in constructors/factories and controlled operations.
- IDs and completed financial snapshots are immutable.
- Use records for immutable value/results when their invariants can be enforced
  clearly; use classes for aggregates with controlled transitions.
- Use `BigDecimal`, scale 2, and the rounding mode approved in `SPEC.md` for all
  money. Never use `double` or `float` for financial values.
- Use enums rather than unconstrained status/type strings.
- Return `List.copyOf`, `Set.copyOf`, `Map.copyOf`, or immutable result records.

## Collections and concurrency

- Declare collection interfaces and justify concrete implementations.
- Use `Map` for ID lookup, `Set` for uniqueness, `BlockingQueue` for work, and
  comparator-produced immutable lists for sorted views.
- Make shared-state ownership explicit. A concurrent collection does not make a
  compound workflow atomic.
- Keep inventory updates atomic per product. Multi-item reservations require a
  journal and exact compensation.
- Use managed executors, bounded waits, and graceful shutdown. Preserve
  interrupt status where appropriate. Never use `Thread.stop`, busy waiting, or
  raw thread-per-order.

## Structure and naming

- Use descriptive class, method, variable, and type names so production code is
  understandable without explanatory comments.
- Do not add comments or Javadocs that restate the code. Keep a comment only for
  a non-obvious reason, concurrency or safety invariant, compatibility
  constraint, workaround, legal requirement, or public contract that naming
  cannot express.
- Constants in `UPPER_SNAKE_CASE`; booleans start with `is`, `has`, `can`, or
  `should`; collections are plural.
- Order members consistently: constants, fields, constructor/factory, public
  methods, private helpers, nested types.
- Keep methods focused and prefer early returns. Extract only meaningful
  behavior or real duplication.
- Use `java.util.logging` in services; `System.out` is allowed only in the CLI
  presentation layer.

## Errors

- Use focused `OrderFlowException` subclasses with useful IDs/context.
- Do not catch generic exceptions unless isolating a worker boundary; preserve
  causes when translating.
- Catch only to handle, translate, compensate, audit/log, or isolate failure.
- Never swallow errors or leave partial mutations.

## Tests

- Follow `.agents/skills/test-driven-development/SKILL.md`.
- Name JUnit 5 tests in Given-When-Then form, for example
  `givenInsufficientStock_whenOrderIsReserved_thenThrowsInsufficientStockException`.
- Structure test bodies with `// Arrange`, `// Act`, and `// Assert` phase
  comments. Omit a phase only when it genuinely does not exist.
- Test behavior, not private implementation. Prefer real in-memory components,
  then deterministic fakes/stubs; use mocks sparingly.
- Concurrency tests use latches/barriers or equivalent coordination, bounded
  timeouts, and observable invariants. Do not rely only on `Thread.sleep`.
- Verify exception message/context and state after failure, not only type.
