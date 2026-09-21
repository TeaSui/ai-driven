---
description: Implement a feature using TDD — write failing tests first, implement until green, verify full suite has no regressions, report complete only with evidence
argument-hint: "<feature description>"
---

Implement: **$ARGUMENTS**

## 1. Study existing test patterns

Read 2–3 test files in this repo to understand: naming, structure, assertion style, mocking approach.

## 2. Write failing tests first

Cover: happy path, edge cases, error conditions.
Tests must reflect requirements, not implementation.

Run them — they MUST fail before writing implementation:
```bash
<test command> --testPathPattern="<new test file>"
```

**If they pass: revise the tests.** You are testing existing behavior, not the new feature.

## 3. Implement across necessary files

Write minimum code to pass the tests.
Do NOT add untested features. Do NOT modify tests to match implementation.

## 4. Run tests — fix if red

```bash
<test command> --testPathPattern="<new test file>"
```

If red: fix implementation only. Re-run. Repeat until green.

## 5. Run full test suite

```bash
<full test command>
```

Fix regressions before reporting complete.

## 6. Report complete only when

- [ ] All new tests: green
- [ ] Full suite: green (0 regressions)
- [ ] No `TODO` / `FIXME` / `mock` in implementation files
- [ ] Build succeeds (if applicable)

If blocked: show the exact failing output. Do not summarize.
