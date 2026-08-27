# OrderFlow Reporting and Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `test-driven-development`. Every behavior follows RED → GREEN → REFACTOR.
> Do not commit unless the user explicitly requests a commit.

**Status:** Implemented — 2026-08-21

**Goal:** Implement `REQ-100`–`REQ-140` and `AC-070`, `AC-090`: stream reports,
CLI demonstration, diagrams, README completeness, and readiness evidence.

**Architecture:** `OrderReporter` reads immutable snapshots from orders,
catalog, customers, and inventory and uses streams/collectors only. CLI
presentation lives outside domain services. `main` only bootstraps.

**Tech stack:** Java 21, Maven Wrapper, JUnit 5, JDK streams.

## Global constraints

- Read `SPEC.md`, `CONTEXT.md`, and
  `docs/specs/orderflow/03-reporting-quality.md`.
- Reports must not use loops; empty input returns non-null immutable results.
- Failed/cancelled orders do not contribute to completed revenue.
- Do not commit unless the user explicitly requests a commit.

## Tasks

### TASK-R01 — Stream reports (`REQ-100`, `AC-070`)

Implement all twelve reports with the required stream operations.

Verification: `./mvnw -Dtest=OrderReporterTest test`

### TASK-R02 — CLI demonstration (`REQ-120`)

≥15 products / 4 categories, ≥10 customers all types, ≥50 orders covering
contention, invalid data, insufficient stock, and payment failure. Wait without
arbitrary sleep. Print summaries, inventory, audit, reports. Shut down every
executor.

Verification: `./mvnw -Dtest=OrderFlowDemonstrationTest test`

### TASK-R03 — Diagrams, README, evidence (`REQ-140`, `AC-090`)

Class diagram, processing sequence diagram, README coverage of collections /
exceptions / streams / concurrency / compensation / shutdown, `AI_USAGE.md`
update. Full `./mvnw clean verify`.
