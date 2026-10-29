# Code Quality Reviewer Prompt Template

Use this template when dispatching a code quality reviewer subagent.

**Purpose:** Verify implementation is well-built (clean, tested, maintainable)

**Only dispatch after spec compliance review passes.**

```
Task tool (code-reviewer):
  Use template at ~/.claude/skills/requesting-code-review/code-reviewer.md

  WHAT_WAS_IMPLEMENTED: [from implementer's report]
  PLAN_OR_REQUIREMENTS: Task N from [plan-file]
  REVIEW_SCOPE: [committed range OR pending Git work OR non-Git manifest; owned paths and checked state]
  BASE_SHA: [verified starting commit, only for committed-range review; otherwise omit]
  HEAD_SHA: [verified ending commit, only for committed-range review; otherwise omit]
  DESCRIPTION: [task summary]
```

**In addition to standard code quality concerns, the reviewer should check:**
- Does each file have one clear responsibility with a well-defined interface?
- Are units decomposed so they can be understood and tested independently?
- Is the implementation following the file structure from the plan?
- Did this implementation create new files that are already large, or significantly grow existing files? (Don't flag pre-existing file sizes — focus on what this change contributed.)

Choose model/effort via `~/.claude/references/model-guide.md`. Use the available Agent tool
(`Task` alias supported). For pending review include staged, unstaged and untracked owned
changes; for non-Git review supply original/changed copies and a hash manifest. Do not require
commits merely to request a review or replace pending scope with the last committed diff.

**Coverage over filtering:** instruct the reviewer to report every issue found, including uncertain or low-severity ones, with a confidence level and severity per finding — the dispatcher filters downstream. Do not add "be conservative" or "high-severity only" language.

**Code reviewer returns:** status + evidence + findings per `~/.claude/rules/subagent-return-format.md` (shape flexible). Map outcomes to status: Critical issues → `BLOCKED` with specifics; Important issues → `DONE_WITH_CONCERNS` with the items listed; Minor/Suggestions-only or clean review → `DONE`. Include strengths, the actual reviewed scope/state, file evidence and exit-criteria checks. Separate inspected/static evidence from executed tests and report skipped/unavailable gates.
