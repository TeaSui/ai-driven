# Workflow Routing

## Trust Boundary Check

Does this change touch **any** of the following? Any single YES → trust boundary.

1. Reads, writes, or logs PII or sensitive data (names, emails, phones, addresses, IDs, financial data, health data, auth credentials).
2. Creates, validates, or mutates auth / session / authorization rules (login, tokens, permissions, roles).
3. Processes money, billing, or third-party payment flows.
4. Exposes a new URL, endpoint, webhook, or public surface consumable outside the trust domain.
5. Integrates with a new external vendor, partner API, or third-party script.
6. Modifies a security-relevant dependency, config key, or secret-handling path (TLS, JWT keys, IAM, encryption).

If unsure on any item, treat as YES. Under-classification is the dominant failure mode.

## Domain Count

A "domain" = a distinct stack surface: backend, frontend, mobile, AWS/infra, data pipeline, AI/LLM, DevOps pipeline. Shared libraries touched by one stack do not add a domain.

## Routing Tiers

| Trust boundary? | Domains | Tier |
|---|---|---|
| **NO** | 1 | **Fast path** |
| **NO** | 2+ | **Coordinated fast path** |
| **YES** | 1 | **Full workflow** |
| **YES** | 2+ | **Orchestrator** |

### Fast path
Implement with TDD + verification. Brainstorming only if design genuinely unclear. No plan document. No worktree. No subagent dispatch. No review loops.

### Coordinated fast path
Multi-domain, no trust boundary (e.g., cross-service refactor, shared-schema rename, DX-only change touching frontend + backend).
- Write a lightweight plan (`writing-plans` skill) so parallel changes stay in sync.
- Dispatch domain leaf agents directly — no Security, no BA, no TechLead.
- Each leaf follows TDD + verification.
- No formal review loops. No orchestrator.
- Integration-check before merge: run the smoke test at the integration point; do not skip because each side passed in isolation.

### Full workflow (single-domain trust boundary)
brainstorming → plan → Security subagent → **TechLead (contract-mode) if architecture or API contract work is needed** → `subagent-driven-development` → API Test + QA (parallel, when service is running).
TechLead is optional for this tier — skip when the change has no new contract surface (e.g., tightening validation on an existing endpoint). When invoked here, TechLead runs in contract-mode ("Stop after Phase 2"); it does NOT delegate — the main session dispatches implementers using custom agent definitions as context.

### Orchestrator (multi-domain trust boundary)
Orchestrator sequences: **brainstorming (or BA dispatch) → Security → TechLead (contract-mode) → implementation → API Test + QA (parallel, when service is running).**
Runtime dependency resolution. Parallel when safe. TechLead runs in contract-mode only; the orchestrator (not TechLead) dispatches implementers.

## Mid-execution domain expansion

If a task begins in one tier but discovers additional scope mid-execution, re-classify before proceeding:

- Fast path → discovers second domain → promote to **coordinated fast path**; write a lightweight plan before touching the second domain.
- Fast path / coordinated fast path → discovers trust boundary → **stop**, promote to full workflow (1 domain) or orchestrator (2+). Do not retrofit Security after the fact on in-flight code; dispatch Security on the unmerged work.
- Full workflow → discovers second domain → promote to **orchestrator**. The existing Security output is reusable; add TechLead contract-mode before fanning out.

Do not silently stay in the lower tier. Promotion is mandatory once the trigger is met.

## Small-change escape hatch

A trust-boundary change may use the tier **one step lighter** if ALL of the following hold:
1. Diff is ≤ 30 lines of production code across ≤ 3 files (test code excluded).
2. No new endpoint, no new external surface, no new dependency, no schema/migration change.
3. The trust-boundary trigger is a touch, not a new decision (e.g., renaming a field already classified as PII, not introducing a new PII field).
4. An existing Security rule or threat model already covers the touched code path (cite the `docs/security/` path).

With all four true: full workflow → fast path with TDD; orchestrator → coordinated fast path. Document the decision in the commit message: "Escape hatch applied: <cite rule>". If any condition fails, use the full tier.

## Ambiguous?

Walk the 6-item checklist above. If any item is YES, full workflow or orchestrator. Otherwise fast path (or coordinated fast path for 2+ domains). Do not rely on a single gut-check question.

## Design Decision Quality

For non-trivial design decisions (architecture, API contracts, skill/agent design, data models), use the Agent tool to challenge your draft before presenting to the user:
1. Draft the design with full context
2. Spawn an agent to find flaws, gaps, and counter-arguments
3. Resolve all counter-arguments — iterate until none remain
4. Present only the final version

Skip for: implementation tasks, bug fixes, simple questions, single-file changes.
