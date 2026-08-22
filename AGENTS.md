# OrderFlow Agent Guidelines

## Scope and precedence

This file is the repository-wide source of truth for coding agents. Platform
instructions and the current user request apply first. Within this repository:

1. An approved `SPEC.md` and its linked `docs/specs/orderflow/` chunks define
   product behavior. `SPEC.md` owns their shared approval status.
2. `CONTEXT.md` defines stable domain vocabulary.
3. `.guidelines/java.md` defines Java conventions.
4. Existing code and tests describe current behavior but do not override an
   approved contract.

The product contract is currently draft until a human explicitly approves
`SPEC.md`. Surface conflicts instead of silently selecting a source.

## Repository purpose

OrderFlow is a plain-Java, in-memory proof of concept for concurrent order,
inventory, payment, fulfilment, audit, and reporting behavior. It deliberately
has no Spring Boot, database, web API, Lombok, or external application framework.

## Required routing

- New features or material behavior changes: read and follow
  `.agents/skills/spec-driven-development/SKILL.md`.
- Catalog, customer, order, validation, or pricing work: read
  `docs/specs/orderflow/01-core-domain.md`.
- Processing, inventory, payment, notification, or exception work: read
  `docs/specs/orderflow/02-fulfilment.md`.
- Reporting, audit, CLI, testing, or delivery evidence: read
  `docs/specs/orderflow/03-reporting-quality.md`.
- Java source, tests, Maven, or dependencies: read `.guidelines/java.md`.
- Implementation or bug fixes: follow
  `.agents/skills/test-driven-development/SKILL.md`.
- Before reporting work ready: follow
  `.agents/skills/verify-feature-readiness/SKILL.md`.
- Domain terminology: read `CONTEXT.md`.

## Ownership and layout

```text
src/main/java/com/codewalnut/orderflow/  production code: core.domain, core.service, core.exception
src/test/java/com/codewalnut/orderflow/  behavior and concurrency tests mirroring packages
SPEC.md                                  product contract (after approval)
docs/specs/orderflow/                    capability contract chunks
CONTEXT.md                               stable domain glossary
docs/superpowers/specs/                  approved design records
.agents/skills/                          reusable agent procedures
.guidelines/                             stable Java conventions
```

Use `core.domain` for models, `core.service` for operations, and
`core.exception` for errors. Create a feature folder only when that
capability is implemented. Do not add empty `web`, `mapper`, `gateway`,
or `config` packages.

## Commands

Use the checked-in Maven Wrapper once available:

```bash
./mvnw clean verify
./mvnw -Dtest=ClassName test
java -cp target/classes com.codewalnut.orderflow.OrderFlowApplication
```

The project targets Java 21. A matching JDK is required for final verification.

## Working boundaries

Always:

- Keep changes scoped to an approved requirement and task.
- Write a failing behavior test before production behavior.
- Use `BigDecimal` for money and immutable copies at collection boundaries.
- Make shared-state ownership and shutdown behavior explicit.
- Preserve unrelated work and inspect the complete diff before handoff.

Ask first:

- Changing the approved spec or Java version.
- Adding dependencies, CI, persistence, networking, or frameworks.
- Changing concurrency strategy, public contracts, or package boundaries.
- Deleting tests or generated/IDE files that may belong to the user.

Never:

- Add secrets, credentials, personal environment data, or build output.
- Use `double`/`float` for money, raw thread-per-order, busy waiting, or
  `Thread.stop`.
- Weaken, skip, or delete a failing test to obtain a green build.
- Start implementation while `SPEC.md` remains unapproved.
- Commit, push, or open a pull request without explicit user authorization.
