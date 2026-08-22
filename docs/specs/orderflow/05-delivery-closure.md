# OrderFlow Delivery-Closure Contract

**Status:** Approved — 2026-08-22 (Q1 yes; Q2 = C, Git/PR deferred).
**Covers:** `REQ-190`–`REQ-220`, `AC-130`–`AC-150`
**Source:** leftover brief items in `docs/requirements/orderflow-poc-brief.txt`
sections 19–22 after Core Domain, Fulfilment, Reporting, and brief-closure
behavior were implemented.

Read `SPEC.md` and `CONTEXT.md` first. This chunk does not add catalog, order,
inventory, payment, or reporting behavior. It closes ratification, diagrams,
environment evidence, Git history, and pull-request delivery.

## Assumptions

1. Product behavior in `REQ-010`–`REQ-180` is already implemented. Approving
   this chunk also **ratifies** [brief closure](04-brief-closure.md): Inventory
   owns stock; `ProductCatalog.availableQuantity` is a read-through; `Product`
   has no quantity field or setter.
2. `CLAUDE.md` remains out of scope unless Claude Code is used.
3. Native JDK 21 verification is environment evidence. If only JetBrains
   Runtime with `--release 21` is available, that fact is recorded and is not a
   product defect.
4. Git history and a pull request are deferred (Q2 = C, 2026-08-22) until
   the user explicitly asks to commit or open a PR. No secrets, credentials,
   `target/`, or IDE files are committed.
5. The OrderFlow tree currently sits untracked inside a parent `TASKS`
   working tree. Delivery Git history is OrderFlow-only, not a commit of
   sibling task folders.
6. No new Maven dependencies, Spring, persistence, or web API.

## Open questions

None. Q1 (Inventory read-through) and Q2 (Git home) were answered 2026-08-22:
Q1 yes; Q2 = C (docs/diagrams/verify only).

## Requirements

### REQ-190: Ratify implemented brief-closure behavior

Upon approval, `docs/specs/orderflow/04-brief-closure.md` and this file become
Approved under `SPEC.md`. `SPEC.md` shall drop the draft addendum warning for
those chunks and record the Inventory read-through decision. No production
behavior change is required for ratification if current tests still prove
`AC-100`–`AC-120`.

### REQ-200: Align diagrams and delivery docs with implemented behavior

Class and sequence diagrams shall match the implemented create/process flow:

- `OrderFactory` records `CREATED` on the shared `AuditLog` before submit.
- `OrderProcessor` records later lifecycle events, including `QUEUED`,
  `PROCESSING`, reservation, payment, release, final status, notification, and
  skip of a cancelled queued order.
- Catalog quantity is Inventory-owned; diagrams must not show a settable
  quantity on `Product`.

`README.md`, `AI_USAGE.md`, and `SPEC.md` status text shall agree with that
behavior. `AC-090` evidence (assumptions, rejected suggestions, verification
commands) stays current.

### REQ-210: Record Java 21 verification evidence

`./mvnw clean verify` shall be run and recorded. Preferred environment is a
native JDK 21. If native JDK 21 is unavailable, record the actual compiler
(`JAVA_HOME`, release flag, test count, exit code) and keep `AC-001` as an
environment checkbox, not a code change.

The suite shall be run at least twice on the same tree after documentation
alignment, with zero failures both times, matching the brief’s “run
repeatedly” requirement.

### REQ-220: Produce Git history and pull-request evidence

After Q2 is answered and this spec is approved, OrderFlow shall have
meaningful Git history covering agentic setup, core domain, fulfilment,
reporting, brief closure, and this delivery pass. A pull request shall include
summary, assumptions, design decisions, and exact test evidence using
`.github/pull_request_template.md`.

Do not commit generated `target/` output, IDE metadata, secrets, or sibling
projects. Do not force-push. Do not open a PR until the user confirms the
remote and Q2.

## Acceptance criteria

### AC-130: Ratified contract matches tests

**Given** approval of Q1,
**when** `./mvnw clean verify` runs,
**then** existing brief-closure tests still pass (inactive product, `CREATED`
audit, quantity read-through, top-five by sold quantity, cause preservation,
repeated contention) and `SPEC.md` lists `REQ-150`–`REQ-180` as Approved.

### AC-140: Diagrams and docs match the tree

**Given** the class and sequence diagrams plus README,
**when** they are compared to `OrderFactory`, `OrderProcessor`, `Product`, and
`ProductCatalog`,
**then** `CREATED` is recorded at factory time, Inventory owns quantity, and
no diagram or README claims a stored Product quantity field.

### AC-150: Delivery evidence exists or is explicitly deferred

**Given** Q2,
**when** the approved git strategy is executed,
**then** either (A/B) OrderFlow has reviewable commits and a PR with
verification evidence and a clean diff, or (C) `AI_USAGE.md` records that
commit/PR were deferred by the human.

**Given** two `./mvnw clean verify` runs after doc alignment,
**when** both finish,
**then** both have exit code 0 and the recorded counts are written in
`AI_USAGE.md`.

## Testing focus

- No new product behavior is required for ratification.
- Diagram/doc work is verified by review against production types, not by a
  new unit test.
- Git/PR work is verified by `git log`, `git status`, and the PR body, not by
  JUnit.

## Out of scope

- New order, inventory, payment, or report features
- Storing quantity on `Product`
- Spring, persistence, web API, extra dependencies
- `CLAUDE.md`
- Committing sibling folders under `TASKS`
- Force-push or rewriting history on a published branch
