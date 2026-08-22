# AI Usage Record

This file records material agent use without storing secrets, raw system prompts,
or unnecessary conversation dumps.

## 2026-08-21 — Initial agentic setup

### Human context

- Source: `docs/requirements/orderflow-poc-brief.txt`
- Request: use `/using-superpowers` and the language-independent agentic
  practices to start the new Java project.
- Existing workspace: IntelliJ Maven skeleton at `POC Project`.

### Skills and workflow used

- `using-superpowers` — checked applicable process skills first.
- `brainstorming` — explored the skeleton, clarified Java version and
  architecture, proposed alternatives, and obtained section-by-section design
  approval before changing files.
- `agentic-engineering-playbook/references/agentic-development.md` — applied the
  initial setup and stopped at Specify.
- `setup-project-agent-toolkit` Java guidance — added plain-Java conventions,
  Maven/JUnit setup, repository skills, and PR template without Spring rules.
- `writing-skills` — kept repository skills reusable and free of product rules.

### Accepted decisions

- Java 21, Maven, and JUnit 5.
- Package root `com.codewalnut.orderflow` with `core.domain`, `core.service`,
  and `core.exception` (feature folders inside those layers).
- No empty `web`, `mapper`, `gateway`, or `config` packages; this PoC has no
  HTTP API or Spring.
- `ConcurrentHashMap.compute` for atomic per-product inventory updates, with a
  deterministic reservation journal and compensation for multi-item failure.
- No CI during initial setup.
- No feature implementation before human approval of `SPEC.md`.
- Progressive specification disclosure: `SPEC.md` remains the canonical approval
  and index document; detailed requirements and acceptance criteria are grouped
  into three capability chunks under `docs/specs/orderflow/`.
- Production code relies on descriptive names instead of comments that repeat
  the implementation. Comments remain allowed only for non-obvious rationale or
  constraints.
- Unit tests use Given-When-Then behavior names and Arrange/Act/Assert phase
  comments.

### Rejected or deferred suggestions

- Java 26 from the generated IntelliJ skeleton: rejected because the brief
  requires Java 17 or 21.
- Spring Boot, persistence, web APIs, Lombok, and external application/logging
  frameworks: rejected by scope.
- Hexagonal architecture: deferred as too heavy for this two-day in-memory PoC.
- CI workflow: deferred by the agreed initial-setup boundary.
- Feature implementation: blocked until the draft specification is approved.

### Errors and environment findings

- The default shell has no discoverable Java runtime.
- IntelliJ includes JetBrains Runtime 25, which can compile with `--release 21`
  for setup verification, but final verification should run on an installed JDK
  21.

### Verification to record

- [x] Maven wrapper generation
- [x] `./mvnw clean verify` targeting release 21 with IntelliJ JetBrains Runtime 25
- [ ] `./mvnw clean verify` using a Java 21 JDK
- [x] Spec approval — all four proposed decisions approved on 2026-08-21
- [x] Core Domain implementation-plan approval — 2026-08-21
- [x] Core Domain task-list approval — 2026-08-21
- [x] Core Domain feature evidence
- [x] Fulfilment concurrency evidence
- [x] Day 2 / brief-closure verify (2026-08-22: 137 tests)

## 2026-08-21 — Core Domain implementation

### TASK-001 Product invariants

- RED: eleven focused `ProductTest` cycles failed for the next missing or
  incorrect behavior before production changes, including compilation failures,
  absent validation, mutable tags, raw null-tag `NullPointerException`, and
  absent controlled operations.
- GREEN: each focused cycle passed after the minimum production change; final
  result was 11 tests with zero failures, errors, or skipped tests.
- REFACTOR/review: validation prepares every replacement before mutation; an
  independent task review required focused null-tag exceptions and rejected
  update state-preservation coverage, then approved the corrected task.
- Verification: `./mvnw clean verify` succeeded targeting Java release 21 using
  the IntelliJ JetBrains Runtime.

### TASK-002 Inventory administration

- RED: twelve focused `InventoryTest` cycles exposed missing registration,
  stock addition, immutable snapshots, duplicate overwrite protection, invalid
  IDs, unknown products, and overflow/state-preservation behavior.
- GREEN: each cycle passed after a minimum change; final result was 12 inventory
  tests and 23 total tests with zero failures, errors, or skipped tests.
- REFACTOR/review: stock registration uses `putIfAbsent`, additions use
  `ConcurrentHashMap.compute`, overflow uses `Math.addExact`, snapshots use
  `Map.copyOf`, and every public invalid-input path raises a contextual domain
  exception. Independent review approved the corrected task.
- Verification: `./mvnw clean verify` succeeded targeting Java release 21 using
  the IntelliJ JetBrains Runtime.

### TASK-003 Product Catalog

- RED: focused `ProductCatalogTest` cycles exposed missing catalog operations,
  contextual exceptions, query validation, immutable queries, deterministic
  sort tie-breakers, and low-stock behavior.
- GREEN: the final focused suite passed 20 tests; the complete suite passed 43
  tests with zero failures, errors, or skipped tests.
- REFACTOR/review: named query/sort methods remain explicit, product ID is the
  final deterministic tie-breaker, and rejected add/stock operations preserve
  catalog and inventory state. Independent review approved the corrected task.
- Model note: Cursor Grok 4.6 returned `resource_exhausted` twice. With human
  approval, the interrupted fix pass and final review used Cursor Grok 4.5 High
  Fast.
- Verification: `./mvnw clean verify` succeeded targeting Java release 21 using
  the IntelliJ JetBrains Runtime.

### TASK-004 Customer Directory

- RED: focused `CustomerDirectoryTest` cycles exposed missing immutable customer
  values, validation, normalized email uniqueness, controlled replacement,
  contextual query errors, and immutable deterministic queries.
- GREEN: the final focused suite passed 11 tests; the complete suite passed 54
  tests with zero failures, errors, or skipped tests.
- REFACTOR/review: `CustomerDirectory` owns ID and `Locale.ROOT` email indexes,
  validates before mutation, and replaces immutable customers during updates.
  Independent review approved the task after unknown-ID and null-type query
  coverage was added.
- Verification: `./mvnw clean verify` succeeded targeting Java release 21 using
  the IntelliJ JetBrains Runtime.

### TASK-005 Order validation pipeline

- RED: focused `OrderValidationPipelineTest` cycles exposed missing named rules,
  immutable ordered composition, exception translation, duplicate stock
  aggregation, and an integer-overflow false-pass.
- GREEN: the final focused suite passed 8 tests; the complete suite passed 62
  tests with zero failures, errors, or skipped tests.
- REFACTOR/review: rules are focused functions, pipeline/rule/results are
  immutable and ordered, lookup failures become named results, stock checks are
  side-effect free, and duplicate quantities use `Math.addExact`. Independent
  review approved the task after overflow coverage was added.
- Verification: `./mvnw clean verify` succeeded targeting Java release 21 using
  the IntelliJ JetBrains Runtime.

### TASK-006 Order creation and transitions

- The first implementation attempt was discarded because only its first
  behavior had a genuine RED; implementing later behavior before tests violated
  the approved TDD workflow.
- RED: the clean reimplementation produced a distinct expected failure before
  each of 11 planned behaviors, plus focused review RED cases for public line
  totals and synchronized outcome readers.
- GREEN: the final focused suites passed 13 tests; the complete suite passed 75
  tests with zero failures, errors, or skipped tests.
- REFACTOR/review: creation validates before construction, duplicate lines use
  exact ordered merging, item/financial snapshots are immutable, transitions
  share one synchronized monitor, and only the approved transition matrix is
  accepted. Independent review approved the corrected task.
- Verification: `./mvnw clean verify` succeeded targeting Java release 21 using
  the IntelliJ JetBrains Runtime.

### TASK-007 Discount Engine

- The first implementation attempt was discarded because it manufactured RED
  by temporarily disabling already-correct stacking/cap behavior.
- RED: the clean rerun introduced only the minimum rule behavior per cycle;
  stacking and cap then failed naturally. The non-negative final invariant was
  honestly recorded as already green because valid amounts plus the 25% cap
  guarantee it.
- GREEN: after context and composition-boundary review tests, the focused suite
  passed 24 tests; the complete suite passed 99 tests.
- REFACTOR/review: rules remain named functions, custom composition is
  validated, thresholds and customer discrimination are covered, eligible
  positive rates stack in order, and money is capped and rounded exactly.
  Independent review approved the corrected task.
- Verification: `./mvnw clean verify` succeeded targeting Java release 21 using
  the IntelliJ JetBrains Runtime.

### TASK-008 Core Domain readiness

- `REQ-010` / `AC-010`: `ProductTest`, `InventoryTest`, and
  `ProductCatalogTest` cover product invariants, controlled inventory,
  uniqueness, updates, immutable queries, sorting, and low stock.
- `REQ-020` / `AC-010`: `CustomerDirectoryTest` covers immutable customers,
  normalized email uniqueness, controlled index-safe updates, and immutable
  deterministic queries.
- `REQ-030` / `AC-020`: `OrderFactoryTest` and `OrderTest` cover validation
  before construction, duplicate merging, snapshots/totals, nonblank IDs,
  immutable items, synchronized state visibility, and the approved transition
  matrix.
- `REQ-040` / `AC-020`: `OrderValidationPipelineTest` covers six named
  side-effect-free rules, ordered immutable composition, duplicate stock
  aggregation, and overflow-safe failures.
- `REQ-050` / `AC-030`: `DiscountEngineTest` covers customer/threshold
  discrimination, stacking, 25% cap, exact money, composition guards, and
  immutable results.
- Focused Core Domain command: 100 tests, zero failures, errors, or skipped
  tests; exit code 0.
- `./mvnw clean verify`: 100 tests, zero failures, errors, or skipped tests;
  release 21 compilation and packaging succeeded; exit code 0.
- Static convention gate: all 100 tests use Given-When-Then names and explicit
  Act/Assert phase comments; production contains no TODO/FIXME, sleeps, or
  floating-point money.
- Scope gate: no Spring, Lombok, database, worker/executor, payment,
  notification, audit, or reporting implementation was introduced.
- IDE diagnostics: no linter errors.
- Whole-feature review: approved after a focused TDD fix rejected missing/blank
  order IDs before validation and construction.

### Remaining verification and repository notes

- No installed JDK 21 was found. Verification used IntelliJ JBR 25 with Maven
  compiler release 21. The native JDK 21 clean-build checkbox remains open.
- The project is entirely untracked inside the parent `/Users/harsh01/TASKS`
  repository. Git status was inspected, but no commit or staging was performed
  because the user did not request it.
- Fulfilment concurrency, reservation/compensation, payment, notification,
  audit, reporting, CLI demonstration, and shutdown checks are later contract
  phases and were not applicable to this Core Domain gate.

## 2026-08-21 — Package layout aligned to domain/service

Human review asked for a `core.domain` vs `core.service` layout like a production
aggregator, without copying unused Spring folders (`web`, `mapper`, `gateway`,
`config`). Models moved under `core.domain`; catalog/directory/inventory/factory/
discount engine moved under `core.service`; exceptions moved under
`core.exception`. `Order` construction is public so the factory can live in the
service package. Behavior is unchanged.

## 2026-08-21 — Fulfilment and reporting implementation

### Human context

Implement remaining brief/contract items using SDD and TDD after Core Domain.

### Skills and workflow

- Repository `spec-driven-development`: plans in `docs/plans/` for fulfilment
  (`REQ-060`–`REQ-090`) and reporting (`REQ-100`–`REQ-140`). Spec was already
  approved; the user asked to implement rather than stop at another plan gate.
- Repository `test-driven-development`: failing tests before production for
  reservation, audit, payment/notification, processor, reports, and CLI.
- `verify-feature-readiness` before reporting the work ready.

### Accepted decisions

- Inventory reservation uses sorted product IDs, a journal, and exact release.
- Duplicate submission uses `ConcurrentHashMap.newKeySet().add`.
- Payment and notification run on dedicated executors; workers do not create
  raw threads.
- Cancelled queued orders remain in the queue; the worker dequeues, audits
  `SKIPPED`, and leaves stock unchanged.
- `Order.createdAt` is recorded at factory construction.
- Available quantity stays on Inventory, not Product.
- CLI printing lives in `OrderFlowDemonstration`; `main` only bootstraps.

### Rejected or deferred

- Putting stock on `Product` (conflicts with `CONTEXT.md`).
- Spring service/web/mapper layers.
- Commit/PR (user did not request).
- Native JDK 21 verify remains deferred if only JBR is installed.

### RED / GREEN evidence

- `InventoryReservationTest`: first cycle failed to compile (`reserve` /
  `Reservation` missing); later insufficient-stock and `release` cycles failed
  until journaled reserve/release existed. Final focused result: 9 tests, 0
  failures.
- `AuditLogTest`: compile failure for missing types, then green including
  timestamp/ID ordering and concurrent recording.
- `PaymentGatewayTest` / `NotificationChannelTest`: compile failure for missing
  types, then green.
- `OrderTest` created-at: compile failure for `getCreatedAt()`, then green.
- `OrderProcessorTest`: compile failure for missing processor, then 8 tests
  covering happy path, duplicates, cancel skip, payment compensation,
  notification isolation, contention, worker isolation, and shutdown.
- `OrderReporterTest`: compile failure for missing reporter; one assertion
  corrected because two completed orders share a UTC day. Then 3 tests green.
- `OrderFlowDemonstrationTest`: compile failure for missing demonstration, then
  green.

### Verification to record

- [x] Focused fulfilment/reporting tests
- [x] `./mvnw clean verify` using IntelliJ JBR 25 with compiler release 21:
      130 tests, 0 failures, 0 errors, 0 skipped, exit code 0
      (superseded by 2026-08-22 brief-closure verify: 137 tests)
- [ ] Native JDK 21 verify

## 2026-08-22 — Day 2 brief-closure implementation

### Human context

- Request: implement remaining Day 2 / brief-closure behavior
  (`REQ-150`–`REQ-180`).
- Used recommended Q1: catalog quantity is Inventory read-through;
  `Product` has no quantity field.

### Skills and workflow used

- Repository `test-driven-development` and `verify-feature-readiness`.
- Existing tests were already written; compile failed because
  `OrderFlowDemonstration` referenced `auditLog` before it was created.

### Implemented behavior

- `ProductCatalog.availableQuantity(id)` reads Inventory.
- Inactive products throw `InactiveProductException` on create; no Order
  and no `CREATED` audit.
- Successful create records `CREATED` on the shared `AuditLog`.
- Payment/notification use `CompletableFuture.runAsync(..., dedicatedExecutor)`.
- Live `getOrDefault` (quantity merge) and `computeIfAbsent` (order index).
- Unexpected payment failures wrap as `PaymentFailedException(orderId, cause)`.
- Top five products rank by sold quantity, then product ID.
- Demonstration prints each audit event.
- Exception contract tests, `@RepeatedTest(3)` contention, README JVM notes.

### RED / GREEN evidence

- GREEN blocked by compile error:
  `OrderFlowDemonstration.java` cannot find symbol `auditLog`.
- Fix: construct `AuditLog` before `OrderFactory`.
- GREEN: `./mvnw clean verify` with IntelliJ JBR 25, `--release 21`:
  137 tests, 0 failures, 0 errors, 0 skipped, exit code 0.

### Verification to record

- [x] Focused closure tests plus `./mvnw clean verify` (137 tests)
- [ ] Native JDK 21 verify
- [ ] Git commit / PR (not requested)

### Residual risk

- Brief-closure was later ratified 2026-08-22; see the delivery-closure
  section below.
- Native JDK 21 still unverified if only JetBrains Runtime is available.

## 2026-08-22 — Delivery closure (ratify, diagrams, verify)

### Human context

- Asked what was left and to complete using SDD and TDD.
- Approved `04` + `05` with Q1 yes (Inventory read-through) and Q2 = C
  (docs/diagrams/verify only; no commits or PR).

### Skills and workflow used

- `spec-driven-development`: Specify remaining delivery contract, then
  Plan/Tasks, then implement docs only.
- `verify-feature-readiness`: two full Maven verifies after diagram alignment.
- No new production behavior; TDD did not add tests.

### Accepted decisions

- Ratify `REQ-150`–`REQ-180` as implemented.
- Inventory owns quantity; catalog read-through; no Product quantity field.
- Defer Git history and a pull request until an explicit request
  (superseded: user requested push/PR on 2026-08-22).

### Rejected or deferred

- Dedicated OrderFlow git repo and PR (Q2 option A).
- Commits on the parent `TASKS` repo (Q2 option B).
- `CLAUDE.md`.
- Native JDK 21: environment still JBR 25 with `--release 21`.

### Verification to record

- [x] Specs `04` and `05` marked Approved; `SPEC.md` open questions cleared.
- [x] Class diagram: `OrderFactory` → `AuditLog`; no Product quantity.
- [x] Sequence diagram: factory `CREATED` before `submit`.
- [x] `./mvnw clean verify` run 1: 137 tests, 0 failures, exit 0
- [x] `./mvnw clean verify` run 2: 137 tests, 0 failures, exit 0
- [x] Compiler: IntelliJ JBR 25.0.3, Maven `compiler.release` 21
- [ ] Native JDK 21 verify
- [x] Git commit / PR requested 2026-08-22: push to
      `Harsha-T-G/orderflow-poc` (author email `harshatg2004@gmail.com`)
      with a pull request, and also push this project to
      `Harsha-T-G/Java-Exercises`.

### Residual risk

- Native JDK 21 remains an environment checkbox.
- `orderflow-poc` push may require GitHub access as `Harsha-T-G`; the
  local `gh` session may be a different account.

