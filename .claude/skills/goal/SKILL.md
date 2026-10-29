---
name: goal
description: Execute an explicitly delegated end-to-end outcome autonomously, including recon, implementation, verification and delivery. Use for /goal or requests to handle the whole task independently; ordinary factual questions do not need this workflow.
---

# Autonomous outcome delivery

Inspect relevant project instructions, existing changes, code and test commands before asking intake questions. Preserve the user's current work. Establish the goal, scope and measurable success criteria from the request and available evidence; ask only when a material constraint cannot be resolved.

Select the tier in `rules/workflow-routing.md`; autonomy does not remove Security, contract, QA or permission boundaries. Fast path uses an in-context checklist, TDD where meaningful and relevant verification, with no formal plan or agent ceremony. Coordinated work uses an owned plan and independent leaves. Trust-boundary work obtains Security and any required contracts before the implementation plan. Multi-domain trust-boundary work uses the orchestrator where available.

For substantial work or sessions that need recovery, persist `.goal/state.md` or the accepted plan location with goal, acceptance evidence, tasks/dependencies/status, decisions and blockers. Avoid scaffolding a state directory for a tiny fix. Update at meaningful checkpoints; reread after resume/compaction. Never recreate unknown state from memory.

Continue authorized reversible work and delegated routine choices. Present material decisions without repeatedly asking for design/spec/execution approval already covered by the request. Keep native permission prompts, destructive operations and external releases inside their separately authorized scope. User messages steer the active goal unless clearly replacing/canceling it.

Implement cohesive tasks and verify with commands/artifacts that establish the actual behavior. Use appropriate tools, not every tool by default. Run tests appropriate to a change; broaden testing when a failure, new change or unresolved concern warrants it. Debug from observed failures. Respect `rules/review-loop-limits.md`; continue independent tasks while blocked dependencies remain stopped.

Before completion, compare every criterion with real evidence and review the integrated changes. Dispatch formal reviewers only in tiers that require/benefit from them. Missing runtime/independent gates stay explicit; never label degraded checks as passing those gates. Do not fabricate a test result, model identity, deployment or source link.

Deliver a concise outcome, important changes, verified checks and material limitations. Link substantial artifacts instead of pasting the entire internal plan. Execute authorized follow-through rather than ending with a generic offer to continue.
