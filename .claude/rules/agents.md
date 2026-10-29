# Agent Delegation Rules

See `rules/workflow-routing.md` for four-tier routing: fast path / coordinated fast path / full workflow / orchestrator.

## Agent Registry
- **agent-orchestrator**: Multi-domain + trust-boundary coordination. NOT the default — earned by multi-domain + trust boundary.
- **tech-lead-subagent**: Architecture decisions, API contracts, implementation coordination. Default dispatch is contract-mode ("Stop after Phase 2", no delegation); full mode (delegates implementers) only for explicit standalone runs outside the routing tiers.
- **security-engineer-subagent**: Threat modeling, security rules. BEFORE implementation for: new auth flows, payment processing, PII handling, new external API exposure. Skip for: internal utilities, UI changes, non-security config updates, existing API field additions that do NOT change the security posture (no new PII field, no auth semantics change). **Do not skip** for changes matching `rules/workflow-routing.md` trust-boundary item 6 (TLS, JWT keys, IAM, encryption, secret-handling paths) — those are trust-boundary config changes and Security is required. When `workflow-routing.md` and this skip list appear to disagree, `workflow-routing.md` wins.
- **business-analyst-subagent**: Requirements clarification, prioritization, MVP scoping. May delegate (UI/UX only) when running standalone; orchestrator-dispatched BA MUST NOT re-dispatch.
- **growth-marketer-subagent**: Measurement, funnel/metric definition, analytics event taxonomy, experiment design, go-to-market. Strategic advisory (peer of BA/Security/TechLead) — produces plans/specs, does NOT code or dispatch; anything actionable it proposes re-enters routing per `rules/workflow-routing.md`.
- **backend-engineer-subagent**: APIs, business logic, databases
- **frontend-engineer-subagent**: UI components, user experience
- **devops-engineer-subagent**: CI/CD, infrastructure, containers
- **data-engineer-subagent**: ETL, data pipelines, warehouses
- **ai-engineer-subagent**: LLM integration, prompt engineering, RAG, agent design, AI cost optimization
- **mobile-engineer-subagent**: Flutter/Dart, iOS (Swift/SwiftUI), Android (Kotlin/Compose), React Native
- **aws-infrastructure-subagent**: AWS CDK, Lambda, Step Functions, DynamoDB, SQS, API Gateway, EventBridge
- **ui-ux-designer-subagent**: Wireframes, component specs, user flow design — activate before frontend when visual design needed
- **api-test-agent**: Performance, security smoke, endpoint smoke, stress testing against running services
- **qa-subagent**: Final validation gate. AFTER implementation. Optional for single-file fixes and trivial changes.
- **code-reviewer**: Reviews completed work against plan and standards. Read-only tools (no Write/Edit). In orchestrator paths (where TechLead is contract-mode and does not run reviews), the orchestrator MUST dispatch code-reviewer after implementation when a structured review is needed — no other agent owns this gate.
- **codebase-mapper**: Read-only exploration agent spawned by `/map-codebase` to write `.planning/codebase/` documents. Returns confirmation only (~10 lines), never document contents. Not part of trust-boundary or orchestrator flows.

Per-dispatch model selection (override the agent's default `model:` frontmatter): see `skills/dispatching-parallel-agents/references/model-routing.md`.

## Dispatch Contract
- Every `Task` dispatch by a delegating agent (orchestrator, tech-lead full-mode, BA-standalone, or the main session dispatching in coordinated-fast-path / full-workflow tiers) MUST follow `rules/dispatch-prompt-contract.md` — the nine contract fields (role, context, inputs, task, exit_criteria, out_of_scope, evidence, bailout, return_format; XML recommended, markdown headings compliant). Review/advisory dispatches use the lighter minimum defined there.
- Dispatch prompts are model-aware: style content for the dispatch's resolved model per the "Model-Specific Steering" section of `skills/dispatching-parallel-agents/references/model-routing.md` (literal scope enumeration for sonnet/opus, coverage-not-filtering for literal reviewers, slot-filling for haiku, terse for frontier overrides).
- Every subagent's return MUST include three things (prose, markdown, or XML — shape is flexible): status ∈ {DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, BLOCKED}, evidence paths the dispatcher can `Read`, and an exit-criteria check. Full conventions: `rules/subagent-return-format.md`.
- Dispatchers verify every cited evidence path and confirm each exit criterion is marked met before accepting `DONE`.
- Fast-path work does not dispatch subagents, so no exemption clause is needed.

## Quality Gates
- Which tiers require a QA gate, and when API Test + QA run in parallel, is owned by `rules/workflow-routing.md` (tier table + per-tier sequences). Do not restate the tier logic here — trust-boundary and multi-domain tiers carry the gate; fast path and coordinated fast path do not.
- Agent-level QA scope (qa-subagent optional for single-file/trivial changes; api-test-agent for running services) is in the Agent Registry entries above.

## Factual Grounding
- All agents MUST follow `rules/reduce-hallucinations.md` — no invented file paths, function names, flags, APIs, or commit SHAs. Cite the source or say "I don't know".
- Dispatchers: treat fabricated evidence paths in subagent returns as BLOCKED, per `rules/subagent-return-format.md`.

## Planning Skills Ordering

Three skills cover plan/execution and can be confused. Use this order:

1. **`writing-plans`** — produces the plan document. Create an initial coordination outline after scope clarification. Persist the implementation plan once Security and any required TechLead contracts exist, before implementation dispatches; advisory/strategic dispatches may precede that plan. Outputs a written plan that downstream skills consume.
2. **`subagent-driven-development`** — executes the plan **within the current session** by dispatching leaf subagents for independent tasks. Use when the plan has parallelizable leaf work and the dispatcher is the main session (or orchestrator). Also use for single-session + checkpoints by pausing between tasks to confirm with the user.
3. **`executing-plans`** — executes the plan **in a separate session with review checkpoints**. Use when the plan spans multiple review gates, or when the user wants human-in-the-loop checkpoints between phases that must survive a context reset.

Decision: single-session + many leaves → `subagent-driven-development` (with or without intra-session checkpoints). Multi-session + checkpoints → `executing-plans`. Both skills assume a `writing-plans` output exists; do not start execution without a plan document for full-workflow / orchestrator paths.

**Plan vs. TechLead contract:** when TechLead (contract-mode) produces a contract/ADR as part of orchestrator or full-workflow, the `writing-plans` output is the execution plan (task breakdown, ordering, parallelism) and the TechLead output is the design artifact (API shape, data model). They are complementary, not duplicative. Write the plan AFTER TechLead's contract exists so tasks can reference concrete contract paths.

Fast-path work skips all three.
