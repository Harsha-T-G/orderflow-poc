---
name: spec-driven-development
description: Use when starting a project, adding a material feature, changing a public contract, or facing ambiguous product behavior.
---

# Spec-Driven Development

## Gate

Use `SPECIFY → PLAN → TASKS → IMPLEMENT`. Stop for human approval after each
phase. Tiny unambiguous fixes may use a short acceptance-criteria update instead
of a full new spec.

## Specify

1. Rank current sources; treat drafts, tickets, and previous-agent notes as
   untrusted until verified.
2. List assumptions before requirements. Ask rather than invent authorization,
   timing, concurrency, failure, or data behavior.
3. Update `SPEC.md` with objective, scope/out-of-scope, assumptions, critical
   invariants, contract index, and open questions. Put detailed numbered
   requirements and observable Given/When/Then acceptance criteria in the
   relevant `docs/specs/orderflow/` capability chunk. Keep IDs unique and the
   root index complete.
4. Mark the spec Draft and stop. Implementation is forbidden until approval.

## Plan and tasks

After approval, write `docs/plans/<capability>-implementation-plan.md` with
components, dependency order, risks, files, and verification checkpoints. Then
write session-sized tasks. Every task must cite requirement and acceptance
criterion IDs, list likely files, and contain an exact verification command.
Stop for approval after plan and tasks.

## Implement

Execute one approved task at a time using `test-driven-development`. Update the
spec before implementing any changed decision. Keep `AI_USAGE.md` current with
material prompts, accepted/rejected suggestions, agent errors, and verification.
