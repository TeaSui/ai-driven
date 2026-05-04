# Workflow Routing

## Trust Boundary Check

Does this change touch: auth/session, payments/billing, PII/sensitive data, or external API surface (new endpoints exposed to clients)?

**NO → Fast path.**
Implement with TDD + verification.
Brainstorming only if design genuinely unclear.
No plan document. No worktree. No review loops.

**YES, single domain → Full workflow.**
Brainstorming → plan → Security subagent → subagent-driven-development → API Test + QA (parallel, when service is running).
Dispatch implementers using custom agent definitions as context.

**YES, multi-domain → Orchestrator.**
Orchestrator sequences: Security → TechLead → implementation → API Test + QA (parallel, when service is running).
Runtime dependency resolution. Parallel when safe.

## Ambiguous?

Ask one question: "Could a mistake here cause a security incident, data breach, or financial loss?" If yes → full workflow. If no → fast path.

## Design Decision Quality

For non-trivial design decisions (architecture, API contracts, skill/agent design, data models), use the Agent tool to challenge your draft before presenting to the user:
1. Draft the design with full context
2. Spawn an agent to find flaws, gaps, and counter-arguments
3. Resolve all counter-arguments — iterate until none remain
4. Present only the final version

Skip for: implementation tasks, bug fixes, simple questions, single-file changes.
