---
name: test-driven-development
description: Use when implementing behavior, fixing a defect, changing logic, or protecting a concurrency invariant.
---

# Test-Driven Development

For each approved behavior, use **RED → GREEN → REFACTOR**.

1. Read the requirement, acceptance criterion, and current code.
2. Write one focused JUnit 5 behavior test. For defects, reproduce the failure.
3. Run the narrowest Maven test command and confirm it fails for the expected
   reason. A test that passes immediately does not prove the new behavior.
4. Write the smallest production change that can satisfy the test.
5. Re-run the focused test, then affected tests. Refactor only while green.
6. Before task completion, run `./mvnw clean verify`.

Name tests in Given-When-Then form, for example
`givenInsufficientStock_whenOrderIsReserved_thenThrowsInsufficientStockException`.
Structure each test with `// Arrange`, `// Act`, and `// Assert` comments,
omitting only a phase that genuinely does not exist. Prefer real in-memory
objects, then deterministic fakes/stubs; mock only boundaries whose real
behavior is slow, non-deterministic, or externally destructive.

Concurrency tests must coordinate with latches, barriers, futures, or equivalent
signals, use bounded timeouts, and assert domain invariants. Do not use sleep as
the only coordination mechanism. Never weaken, skip, or delete a failing test
to obtain green output. Record RED and GREEN commands/results in `AI_USAGE.md`
or the task evidence.
