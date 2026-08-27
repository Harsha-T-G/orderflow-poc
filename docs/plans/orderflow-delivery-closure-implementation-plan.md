# OrderFlow Delivery-Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use repository
> `test-driven-development` only if production behavior changes. This pass is
> ratification, diagrams, and verification. Do not commit unless the user
> explicitly requests a commit.

**Status:** Implemented — 2026-08-22 (Q1 yes, Q2 = C: no git/PR)

**Goal:** Ratify `REQ-150`–`REQ-180`, align diagrams/docs with implemented
behavior, run `./mvnw clean verify` twice, and record that Git/PR are deferred.

**Architecture:** No new domain services. Specs become Approved. Class and
sequence diagrams gain factory `CREATED` audit and Inventory-owned quantity.
Existing tests already cover `AC-100`–`AC-120`.

**Tech stack:** Java 21, Maven Wrapper, JUnit 5; Mermaid diagrams; no new
dependencies.

## Global constraints

- Do not store quantity on `Product`.
- Do not add Spring, persistence, web API, or extra dependencies.
- Do not commit or open a PR in this pass (`05` Q2 = C).
- Do not weaken, skip, or delete tests.

## Planned files

```text
SPEC.md
docs/specs/orderflow/04-brief-closure.md
docs/specs/orderflow/05-delivery-closure.md
docs/diagrams/orderflow-class-diagram.md
docs/diagrams/orderflow-processing-sequence.md
README.md          (only if it disagrees with Inventory-owned quantity)
AI_USAGE.md
```

## Tasks

### TASK-D01 — Ratify contracts (`REQ-190`, `AC-130`)

Mark `04` and `05` Approved. Record Q1 = yes (Inventory read-through) and
Q2 = C (no commit/PR). Add approved decision for Inventory-owned quantity.
Clear `SPEC.md` open questions.

Verification: `SPEC.md` lists `REQ-150`–`REQ-220` as Approved.

### TASK-D02 — Align diagrams (`REQ-200`, `AC-140`)

Update class diagram: `OrderFactory` depends on `AuditLog`; catalog exposes
`availableQuantity`; `Product` has no quantity field.

Update sequence diagram: factory records `CREATED` before `submit`.

Verification: diagrams match `OrderFactory`, `OrderProcessor`, `Product`,
and `ProductCatalog`.

### TASK-D03 — Verify twice (`REQ-210`, `AC-130`, `AC-150`)

Run `./mvnw clean verify` twice with the available JDK. Record commands,
test counts, exit codes, and whether native JDK 21 was used.

Verification: both runs exit 0.

### TASK-D04 — Record deferred Git/PR (`REQ-220`, `AC-150`)

Update `AI_USAGE.md`: human deferred commits and pull request. Do not run
`git commit` or `gh pr create`.

Verification: `git status` still shows no new commit created by this pass.
