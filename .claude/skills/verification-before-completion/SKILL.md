---
name: verification-before-completion
description: Use when about to claim work is complete, fixed, or passing, before committing or creating PRs - requires running verification commands and confirming output before making any success claims; evidence before assertions always
---

# Verification Before Completion

## Overview

Claiming work is complete without verification is dishonesty, not efficiency.

**Core principle:** Evidence before claims, always.

**Violating the letter of this rule is violating the spirit of this rule.**

## The Iron Law

```
NO COMPLETION CLAIMS WITHOUT APPLICABLE VERIFICATION EVIDENCE
```

Tie evidence to the exact checked state and scope: commit/base or pending-file manifest, command, result and relevant environment/tool version. Reuse earlier inspected results when that state/scope is unchanged and no failure or uncertainty invalidates them, per `~/.claude/rules/reduce-hallucinations.md`. Rerun after relevant edits, dependency/config changes or new failures, or when the user explicitly asks for a fresh challenge such as `/prove-it`.

## The Gate Function

```
BEFORE claiming any status or expressing satisfaction:

1. IDENTIFY: What applicable check proves this claim, for which target/scope?
2. CHECK STATE: Reuse valid inspected evidence or run the required command if absent/invalidated
3. READ: Inspect decisive output, check exit code, count failures; redact sensitive values
4. VERIFY: Does output confirm the claim?
   - If NO: State actual status with evidence
   - If YES: State claim WITH evidence
5. ONLY THEN: Make the claim

Skip any step = lying, not verifying
```

## Common Failures

| Claim | Requires | Not Sufficient |
|-------|----------|----------------|
| Tests pass | Applicable test output: 0 failures for the checked state/scope | Invalidated run, "should pass" |
| Linter clean | Linter output: 0 errors | Partial check, extrapolation |
| Build succeeds | Build command: exit 0 | Linter passing, logs look good |
| Bug fixed | Test original symptom: passes | Code changed, assumed fixed |
| Regression test works | Red-green cycle verified | Test passes once |
| Agent completed | Read changed files/diff, cited evidence and exit-criteria results | Agent reports "success" |
| Requirements met | Line-by-line checklist | Tests passing |

## Red Flags - STOP

- Using "should", "probably", "seems to"
- Expressing satisfaction before verification ("Great!", "Perfect!", "Done!", etc.)
- About to commit/push/PR without verification
- Trusting agent success reports
- Relying on partial verification
- Thinking "just this once"
- Tired and wanting work over
- **ANY wording implying success without applicable evidence for the actual reviewed state/scope**

## Rationalization Prevention

| Excuse | Reality |
|--------|---------|
| "Should work now" | RUN the verification |
| "I'm confident" | Confidence ≠ evidence |
| "Just this once" | No exceptions |
| "Linter passed" | Linter ≠ compiler |
| "Agent said success" | Verify independently |
| "I'm tired" | Exhaustion ≠ excuse |
| "Partial check is enough" | Scope the claim to what ran; partial checks do not establish full validation |
| "Different words so rule doesn't apply" | Spirit over letter |

## Key Patterns

**Tests:**
```
✅ [Run test command] [See: 34/34 pass] "All tests pass"
❌ "Should pass now" / "Looks correct"
```

**Regression tests (TDD Red-Green):**
```
✅ Write requirement-based test → Safely isolate pre-change behavior → Run (expected failure) → Apply owned fix → Run (pass)
❌ "I've written a regression test" (without red-green verification)
```

Only reverse a known owned patch in isolation; preserve a recoverable copy and never reset/delete user or other-agent work. See `test-driven-development` for safe recovery.

**Build:**
```
✅ [Run build] [See: exit 0] "Build passes"
❌ "Linter passed" (linter doesn't check compilation)
```

**Requirements:**
```
✅ Re-read plan → Create checklist → Verify each → Report gaps or completion
❌ "Tests pass, phase complete"
```

**Agent delegation:**
```
✅ Agent reports success → Read actual changes and cited evidence → Confirm scope/state → Report actual state
❌ Trust agent report
```

## Why This Matters

From 24 failure memories:
- your human partner said "I don't believe you" - trust broken
- Undefined functions shipped - would crash
- Missing requirements shipped - incomplete features
- Time wasted on false completion → redirect → rework
- Violates: "Honesty is a core value. If you lie, you'll be replaced."

## When To Apply

**ALWAYS before:**
- ANY variation of success/completion claims
- Committing, PR creation and task completion where a claim depends on checks
- Moving to a dependent task or reporting a delegated task complete

**Rule applies to:**
- Exact phrases
- Paraphrases and synonyms
- Implications of success
- ANY communication suggesting completion/correctness

## Related

- **`grounding-claims`** — this skill applies to *success* claims (tests pass, build works, bug fixed). `grounding-claims` applies to *all* factual claims (file paths, function names, library APIs, commit SHAs). Both enforce: evidence before assertion.

## The Bottom Line

**No shortcuts for verification.**

Read applicable evidence for the checked state and scope, rerun when invalidated, then claim only what it proves. Distinguish PASS, FAIL, SKIPPED, UNSUPPORTED and static-only checks. A parser does not prove runtime behavior, and a missing service check cannot be reported as passing.

This is non-negotiable.
