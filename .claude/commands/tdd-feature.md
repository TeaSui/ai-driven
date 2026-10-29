---
description: Implement a feature using TDD — write failing tests first, implement until green, verify full suite has no regressions, report complete only with evidence
argument-hint: "<feature description>"
---

Implement: **$ARGUMENTS**

First classify the change using `~/.claude/rules/workflow-routing.md`; complete required Security/contract prerequisites before implementation. Follow `~/.claude/rules/dispatch-prompt-contract.md` and `rules/subagent-return-format.md` when dispatching. Use `test-driven-development` for behavior changes and safe recovery; prose/config-only work uses applicable structural or isolated checks. Preserve existing/user/other-agent work and reuse existing authorization.

Discover the project's installed test runner/version from instructions, manifests, lockfiles and CI scripts. Use its supported selector/help; never assume a Jest-specific flag works for every runner. Do not download dependencies or start services merely to execute this command.

## 1. Study existing test patterns

Read 2–3 test files in this repo to understand: naming, structure, assertion style, mocking approach.

## 2. Write failing tests first

Cover: happy path, edge cases, error conditions.
Tests must reflect requirements, not implementation.

Run them — they MUST fail before writing implementation:
```bash
<project-supported command selecting the new test file>
```

**If they pass:** check whether the requirement is already implemented or the test misses the change. Demonstrate the expected failure against pre-change behavior safely; do not force an unrelated failure or delete existing work.

## 3. Implement across necessary files

Write minimum code to pass the tests.
Do not add untested features or weaken assertions to match incorrect implementation. Correct a test only when the requirement/evidence shows the test itself is wrong.

## 4. Run tests — fix if red

```bash
<project-supported command selecting the new test file>
```

If red: diagnose the failure, fix the responsible owned code (or a demonstrably incorrect test), then rerun. Preserve unrelated work and respect applicable review-loop limits.

## 5. Run full test suite

```bash
<full test command>
```

Fix regressions before reporting complete.

## 6. Report complete only when

- [ ] Applicable new/regression checks: green with expected failure evidence for behavior changes
- [ ] Required affected/full suite: green for the stated scope; skipped/unavailable gates are explicit
- [ ] No unresolved placeholder behavior in changed production code; inspect keyword matches in context
- [ ] Build succeeds (if applicable)

Report the actual target, commands, decisive results and limits. If blocked, preserve failing evidence and show relevant output with secrets/PII redacted; never claim skipped checks passed.
