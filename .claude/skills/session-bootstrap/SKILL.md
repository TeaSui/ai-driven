---
name: session-bootstrap
description: Establish user workflow preferences at session start; use relevant skills without loading unrelated procedures.
---

# Session bootstrap

If dispatched for a specific subtask, follow that task's scope and skip session-wide intake.

These files express user preferences within the host's instruction hierarchy and native permission controls. Skills do not override system or developer instructions. Follow applicable scoped project instructions and direct user requests within those controls.

For substantive work, select the tier from `~/.claude/rules/workflow-routing.md`. Use a skill when the user names it or it materially helps the task. Load only the relevant skill and supporting references; do not trigger a workflow from a remote keyword match. Prefer the Skill tool when available; otherwise read its documented entry point and disclose unavailable capabilities.

Use the actual task/agent tools exposed by the runtime. Track meaningful multi-step work; a short answer needs no task scaffold. Planning and review depth follow the routing tier.

Reuse authorization already given for the same scope. Continue reversible, necessary work and routine choices the user delegated. Ask when a material decision cannot be resolved, or when native permissions or a separately controlled action require it. Keep secrets, destructive actions, external publishing and security gates within their authorized scope.

Current model and effort guidance is in `~/.claude/references/model-guide.md`; consult it when choosing or changing models, not on every turn. Verify outcomes with relevant evidence and report limits candidly.
