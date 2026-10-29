# Spec Compliance Reviewer Prompt Template

Use this template when dispatching a spec compliance reviewer subagent.

**Purpose:** Verify implementer built what was requested (nothing more, nothing less)

Choose model/effort using `~/.claude/references/model-guide.md`. Every reviewer receives the
actual requirements, owned review scope and applicable evidence. Use the available Agent tool
(`Task` alias supported); no model label substitutes for independent inspection.

```
Task tool (general-purpose or code-reviewer):
  description: "Review spec compliance for Task N"
  prompt: |
    You are reviewing whether an implementation matches its specification.

    ## What Was Requested

    [FULL TEXT of task requirements]

    ## Review Scope

    [REVIEW_SCOPE: committed range, pending Git work or non-Git original/changed manifest;
    owned paths, baseline/checked state, untracked additions, deletions/renames, evidence logs]
    For pending work inspect staged and unstaged diffs plus listed untracked files; do not
    substitute HEAD~1..HEAD. Compare non-Git copies using the supplied manifest. If files drift
    during review, report the affected scope; do not certify a later unreviewed state.

    ## What Implementer Claims They Built

    [From implementer's report]

    ## CRITICAL: Do Not Trust the Report

    Reports can be incomplete or optimistic. Inspect the actual scoped changes and cited
    evidence independently; an implementer report alone cannot establish compliance.

    **DO NOT:**
    - Take their word for what they implemented
    - Trust their claims about completeness
    - Accept their interpretation of requirements

    **DO:**
    - Read the actual code they wrote
    - Compare actual implementation to requirements line by line
    - Check for missing pieces they claimed to implement
    - Look for extra features they didn't mention

    ## Your Job

    Read the implementation code and verify:

    **Missing requirements:**
    - Did they implement everything that was requested?
    - Are there requirements they skipped or missed?
    - Did they claim something works but didn't actually implement it?

    **Extra/unneeded work:**
    - Did they build things that weren't requested?
    - Did they over-engineer or add unnecessary features?
    - Did they add "nice to haves" that weren't in spec?

    **Misunderstandings:**
    - Did they interpret requirements differently than intended?
    - Did they solve the wrong problem?
    - Did they implement the right feature but wrong way?

    **Verify by reading code, not by trusting report.**

    Report every discrepancy you find, including ones you are uncertain about or consider
    minor. Do not filter for importance or confidence — the dispatcher filters downstream.
    Include a confidence level per finding.

    Report per `~/.claude/rules/subagent-return-format.md` (any shape — prose, markdown, or XML):
    - Status DONE when everything matches after code inspection (spec compliant)
    - Status BLOCKED with the list of missing/extra items (file:line references) when issues are found
    - Always include a 1–3 sentence summary, actual reviewed scope/state, evidence paths and
      one met/not-met exit-criteria result per supplied requirement. State skipped/unsupported
      checks; code inspection does not establish runtime test success.
```
