---
name: writing-plans
description: Write an actionable implementation plan for substantial or coordinated work once requirements and applicable security/contracts are established.
---

# Implementation plans

Use the routing tier to decide whether a written plan is needed. Fast path uses an in-context checklist. Before strategic dispatches, an initial coordination outline may state dependencies and intended outputs; the implementation plan follows the actual Security/TechLead artifacts and precedes implementers.

Save substantial plans to `docs/plans/YYYY-MM-DD-<topic>.md` or the user's chosen location. Include goal, accepted design, scope exclusions, canonical inputs and validation. In existing/non-Git workspaces, preserve the user's files and workflow; no automatic git initialization, worktree or commit is required just to persist a plan. Use isolation when concurrent work or risky changes warrant it and the runtime supports it.

For each task record:
- Outcome and exact owned files/modules, marking new paths as new.
- Dependencies and strategic/security constraints; cite real artifacts.
- Observable exit criteria and exact verification appropriate to the change.
- Integration owner, relevant failure/rollback behavior and handoff evidence.

Include code when it pins a contract or a fragile operation, not a duplicate completed implementation for every step. Let implementers choose local details inside the contract. State versions and APIs only when verified. Prefer cohesive independently verifiable tasks over fixed file-count or minute limits.

Review for missing requirements, invented dependencies, conflicting ownership and vague acceptance criteria. If implementation is already authorized, proceed in the current session using subagent-driven-development where useful; do not ask the user to choose an internal execution mechanism again. Use executing-plans for a resumed/separate-session plan. Track substantive progress with available task tools or the plan's checkboxes.
