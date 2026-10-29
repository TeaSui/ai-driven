---
name: requesting-code-review
description: Use when completing tasks, implementing major features, or before merging to verify work meets requirements
---

# Requesting Code Review

Dispatch code-reviewer subagent to catch issues before they cascade. The reviewer gets precisely crafted context for evaluation — never your session's history. This keeps the reviewer focused on the work product, not your thought process, and preserves your own context for continued work.

**Core principle:** Review early, review often.

## When to Request Review

**Required when the routing tier/accepted plan calls for independent review:**
- Substantial implementation, trust-boundary changes and formal pre-merge gates
- Respect `~/.claude/rules/workflow-routing.md`; a fast-path fix uses appropriate local verification without mandatory subagent ceremony.

**Optional but valuable:**
- When stuck (fresh perspective)
- Before refactoring (baseline check)
- After fixing complex bug

## How to Request

**1. Identify the actual review scope:**
Select one `REVIEW_SCOPE` and record its checked state:

- **Committed range:** verify the task's actual base/head refs, then inspect their diff. Never assume the previous commit represents the task.
- **Pending Git work:** inspect staged and unstaged diffs plus untracked owned files. Include deletions/renames and record baseline/content hashes or an equivalent snapshot so the reviewer can detect drift.
- **Non-Git work:** supply the owned changed-file manifest with original/changed copies and hashes.

For pending scope collect filenames without losing spaces/newlines:
```bash
git diff --name-only --no-renames -z
git diff --cached --name-only --no-renames -z
git ls-files --others --exclude-standard -z
```
Deduplicate with a NUL-aware collector and pass paths as arguments, never interpolated shell code. Review the actual scoped content; exclude unrelated/user/other-agent changes from the claimed work without hiding relevant context. Do not commit merely to enable review.

**2. Dispatch code-reviewer subagent:**

Use the available Agent tool (`Task` alias supported) with code-reviewer type and the template at `~/.claude/skills/requesting-code-review/code-reviewer.md`. Choose model/effort from `~/.claude/references/model-guide.md`.

**Placeholders:**
- `{WHAT_WAS_IMPLEMENTED}` - What you just built
- `{PLAN_OR_REQUIREMENTS}` - What it should do
- `{REVIEW_SCOPE}` - Mode (committed/pending/non-Git), owned paths, baseline and actual checked state/manifest
- `{BASE_SHA}` - Verified starting commit; required only for committed-range review
- `{HEAD_SHA}` - Verified ending commit; required only for committed-range review
- `{DESCRIPTION}` - Brief summary

**3. Act on feedback:**
- Fix Critical issues immediately
- Fix Important issues before proceeding
- Note Minor issues for later
- Push back if reviewer is wrong (with reasoning)

## Example

```
[Just completed Task 2: Add verification function]

You: Let me request code review before proceeding.

[Inspect staged/unstaged diffs, listed untracked files and original baseline]
[Capture the owned changed-file manifest/content hashes]

[Dispatch code-reviewer subagent]
  WHAT_WAS_IMPLEMENTED: Verification and repair functions for conversation index
  PLAN_OR_REQUIREMENTS: Task 2 from docs/plans/deployment-plan.md
  REVIEW_SCOPE: pending Git work; owned verification/repair files and tests;
                baseline and content hashes in the supplied review manifest
  DESCRIPTION: Added verifyIndex() and repairIndex() with 4 issue types

[Subagent returns]:
  Strengths: Clean architecture, real tests
  Issues:
    Important: Missing progress indicators
    Minor: Magic number (100) for reporting interval
  Assessment: Ready to proceed

You: [Fix progress indicators]
[Continue to Task 3]
```

## Integration with Workflows

**Subagent-Driven Development:**
- Review substantial owned tasks at the checkpoints required by the tier/plan
- Catch issues before they compound
- Fix before moving to next task

**Executing Plans:**
- Review at the accepted checkpoints, sized to integration risk
- Get feedback, apply, continue

**Ad-Hoc Development:**
- Review before merge
- Review when stuck

## Red Flags

**Never:**
- Skip a review gate required by the routing tier or accepted plan
- Ignore Critical issues
- Proceed with unfixed Important issues
- Argue with valid technical feedback

**If reviewer wrong:**
- Push back with technical reasoning
- Show code/tests that prove it works
- Request clarification

See template at: `~/.claude/skills/requesting-code-review/code-reviewer.md`. Review results must name the checked state/scope and contain status, existing evidence paths and exit-criteria checks per `rules/subagent-return-format.md`. A changed file after review invalidates only the affected scope; recheck it before carrying the verdict forward.
