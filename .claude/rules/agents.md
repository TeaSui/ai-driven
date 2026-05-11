# Agent Delegation Rules

See `rules/workflow-routing.md` for three-tier routing: fast path / full workflow / orchestrator.

## Agent Registry
- **agent-orchestrator**: Multi-domain + trust-boundary coordination. NOT the default — earned by multi-domain + trust boundary.
- **tech-lead-subagent**: Architecture decisions, API contracts, implementation coordination
- **security-engineer-subagent**: Threat modeling, security rules. BEFORE implementation for: new auth flows, payment processing, PII handling, new external API exposure. Skip for: internal utilities, UI changes, config updates, existing API field additions.
- **business-analyst-subagent**: Requirements clarification, prioritization, MVP scoping
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

## Dispatch Contract
- Every `Task` dispatch by a delegating agent (orchestrator, tech-lead full-mode, BA-standalone) MUST follow `rules/dispatch-prompt-contract.md` — XML-tagged prompt with role, context, inputs, task, exit_criteria, out_of_scope, evidence, bailout, return_format.
- Every subagent's return MUST include three things (prose, markdown, or XML — shape is flexible): status ∈ {DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, BLOCKED}, evidence paths the dispatcher can `Read`, and an exit-criteria check. Full conventions: `rules/subagent-return-format.md`.
- Dispatchers verify every cited evidence path and confirm each exit criterion is marked met before accepting `DONE`.
- Fast-path work does not dispatch subagents, so no exemption clause is needed.

## Quality Gates
- Never skip quality gates for trust-boundary changes (auth, payments, PII, external API) or multi-domain changes
- Fast-path changes (no trust boundary) do not require QA — TDD + verification is sufficient
- QA can run parallel with API Test agent
