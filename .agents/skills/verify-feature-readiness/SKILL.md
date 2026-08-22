---
name: verify-feature-readiness
description: Use when preparing to report an OrderFlow feature or fix ready, commit, open a pull request, or check specification, tests, concurrency, documentation, and repository hygiene.
---

# Verify Feature Readiness

1. Read `AGENTS.md`, the approved `SPEC.md`, the affected
   `docs/specs/orderflow/` chunk, and routed guidance.
2. Inspect Git status and the complete diff, including untracked files. Exclude
   unrelated, generated, IDE, secret, and environment files.
3. Map every changed behavior to its requirement, test, and evidence.
4. Run focused tests, then `./mvnw clean verify` with JDK 21.
5. For concurrency changes, run deterministic contention, duplicate-submission,
   compensation, worker-isolation, and shutdown checks as applicable. Do not
   replace coordination with timing guesses.
6. Confirm money scale/rounding, immutable collection boundaries, contextual
   exceptions, exact compensation, executor shutdown, and documentation/diagram
   agreement.
7. Report exact commands, exit codes, skipped checks with reasons, unresolved
   assumptions, and residual risk. Update `AI_USAGE.md` and PR evidence.

Never weaken a check or claim readiness while required verification is failing
or unavailable. Distinguish product defects from environment blockers.
