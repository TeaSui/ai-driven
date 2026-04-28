# Review Loop Iteration Limits

These limits apply to full workflow and orchestrator paths only. Fast-path changes have no formal review loops per workflow-routing.md.

When using subagent-driven-development or any workflow with review loops:

## Spec Compliance Review Loop
- **Max 2 fix rounds per task.** Reviewer finds issues → implementer fixes (round 1) → reviewer finds issues again → implementer fixes (round 2) → if reviewer still finds issues → STOP.
- Escalate to human: "Task N has failed spec compliance after 2 fix rounds. The spec may be ambiguous or the task scope may be wrong."
- Do NOT keep looping. Something is structurally wrong.

## Code Quality Review Loop
- **Max 2 fix rounds per task.** Same rule as spec compliance.
- If quality issues persist after 2 fix rounds, the implementation approach needs rethinking, not more patches.

## Combined Limit
- **Max 4 total review iterations per task** (spec + quality combined).
- If a task consumes 4+ review rounds, it's too complex for a single dispatch. Split it.

## Implementer Fix Rounds
- When reviewer sends implementer back to fix:
  - Fix round 1: Normal — address specific issues
  - Fix round 2: Concerning — check if issues are symptoms of a deeper problem. If issues persist after fix round 2 → STOP and escalate. Do not begin a third fix round.

## What "Escalate" Means
1. Report to human: which task, what was tried, what keeps failing
2. Propose: split the task, simplify the spec, or change the approach
3. Wait for human decision before continuing
