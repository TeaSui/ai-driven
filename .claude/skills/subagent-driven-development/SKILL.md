---
name: subagent-driven-development
description: Execute an implementation plan in the current session using owned specialist tasks, dependency ordering and evidence-based reviews.
---

# Execute with specialists

Read the accepted plan and applicable canonical Security/contracts. Resolve dependencies before dispatch. Use the actual Agent/Task tools available in the runtime; never claim a dispatch or independent review occurred when unavailable. Model/effort choices follow `~/.claude/references/model-guide.md`.

For each substantial task:
1. Supply goal, role, task-specific context, real inputs, file ownership, exclusions, exit criteria, evidence and bailout via `rules/dispatch-prompt-contract.md`.
2. Tell implementers they are not alone in the checkout: accommodate others' edits and never revert their work.
3. Dispatch independent tasks concurrently only with non-overlapping ownership and resolved prerequisites. Sequence shared-file changes, Security/contract dependencies and integration.
4. Implementers run meaningful tests for behavior/code changes and preserve the project's conventions. Reversible prose/config edits need appropriate structural/runtime checks, not tests that merely mirror their text.
5. Read returned status, evidence and exit checks per `rules/subagent-return-format.md`. Resolve material concerns before using dependent results.
6. Review specification compliance, then code quality for substantive implementation. The routing tier controls formal review requirements; a leaf's self-review does not replace a required independent gate.
7. Address verified findings and rerun relevant checks. Review bounds: `rules/review-loop-limits.md`; change approach or escalate when the cap is hit.

After tasks converge, run the integration checks and required QA/API tests from the routing tier. API tests need a running service. Record missing runtime/independent gates as concerns or blockers; static inspection is not their substitute.

Use an isolated checkout only when needed and supported. No dependency on an absent using-git-worktrees skill is required. Do not initialize a repository or commit generated planning files without authorization. Preserve user changes and obtain separately required native permissions. Continue work already authorized; ask only for decisions/constraints that materially block it.

For repository delivery, use finishing-a-development-branch if applicable and execute the already-authorized delivery choice. Report outcome, evidence, material limits and concrete next action.
