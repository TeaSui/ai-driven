# Architecture Decision Records (ADRs)

> **Vision:** ADRs exist to capture the "why" behind significant architectural shifts. They prevent repeated debates and provide crucial context for future engineers maintaining this AI-driven system.

This directory contains architectural decisions for the ai-driven system. Each ADR is immutable once completed and serves as a historical design log.

Format follows [ADR Template](./ADR-000-template.md). New ADRs should be numbered sequentially and follow semantic versioning if superseded.

## Strategic ADRs (Standalone Files)

These ADRs document the major architectural decisions shaping the system's runtime, orchestration, and AI integration strategy.

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-001](./ADR-001-step-functions-pipeline-orchestration.md) | Step Functions for Pipeline Orchestration | ACCEPTED | 2026-01-15 |
| [ADR-002](./ADR-002-servicefactory-over-spring-di.md) | ServiceFactory over Spring DI | SUPERSEDED | 2026-01-20 |
| [ADR-003](./ADR-003-sqs-fifo-dynamodb-agent-state.md) | SQS FIFO + DynamoDB for Agent State | ACCEPTED | 2026-02-10 |
| [ADR-004](./ADR-004-custom-resilience-over-resilience4j.md) | Custom Resilience over Resilience4j | ACCEPTED | 2026-02-15 |
| [ADR-005](./ADR-005-toolprovider-toolregistry-pattern.md) | ToolProvider and ToolRegistry Pattern | ACCEPTED | 2026-02-20 |
| [ADR-006](./ADR-006-spring-ai-library-only-adoption.md) | Spring AI Library-Only Adoption | SUPERSEDED | 2026-03-20 |
| [ADR-007](./ADR-007-hybrid-lambda-fargate-architecture.md) | Hybrid Lambda + Fargate Architecture | PROPOSED | 2026-03-21 |

---

## Inline ADRs (Design Decisions)

These shorter-form ADRs capture focused design decisions made during implementation.

- [ADR-008: Separate webhook HMAC secret from API PAT credentials](#adr-008-separate-webhook-hmac-secret-from-api-pat-credentials)
- [ADR-009: Skip verification when secret not configured](#adr-009-skip-verification-when-secret-not-configured)
- [ADR-010: SQS FIFO for agent task ordering](#adr-010-sqs-fifo-for-agent-task-ordering)
- [ADR-011: Single shared Jira webhook token](#adr-011-single-shared-jira-webhook-token)
- [ADR-012: Single default OperationContext in MCP servers](#adr-012-single-default-operationcontext-in-mcp-servers)
- [ADR-013: Claude Model Selection & Fallback Strategy](#adr-013-claude-model-selection--fallback-strategy)
- [ADR-014: Incremental vs Full-Repo Context Strategy](#adr-014-incremental-vs-full-repo-context-strategy)

---

## ADR-008: Separate webhook HMAC secret from API PAT credentials

**Status:** ACCEPTED
**Related:** Security

**Decision**: `GITHUB_AGENT_WEBHOOK_SECRET_ARN` stores a plain 40-char string. `GITHUB_SECRET_ARN` stores a JSON dictionary.

**Rationale**: HMAC secrets are raw strings; API PATs require username context. Mixing them requires JSON parsing in a security-validation hot path.

---

## ADR-009: Skip verification when secret not configured

**Status:** ACCEPTED
**Related:** Security, Rollout Strategy

**Decision**: `WebhookValidator` WARN-logs and returns normally when expected secret is null.

**Rationale**: Enables deploy-first, configure-second rollouts. Monitored via CloudWatch alarm on the `WARN` pattern.

---

## ADR-010: SQS FIFO for agent task ordering

**Status:** ACCEPTED
**Related:** Concurrency, Architecture

**Decision**: Agent tasks enqueue to SQS FIFO with `messageGroupId = ticketKey`.

**Rationale**: Guarantees sequential execution of multiple `@ai` comments on the same ticket, preventing context overrides.

---

## ADR-011: Single shared Jira webhook token

**Status:** ACCEPTED
**Related:** Security

**Decision**: Pipeline and Agent components both source `JIRA_WEBHOOK_SECRET_ARN`.

**Rationale**: Streamlines webhook administration in Jira for operators.

---

## ADR-012: Single default OperationContext in MCP servers

**Status:** ACCEPTED
**Related:** Architecture, MCP

**Decision**: Initial MCP servers default to an implicit single-tenant configuration.

**Rationale**: Multi-org capability requires HTTP/SSE header transport logic outside initial phase scope.

---

## ADR-013: Claude Model Selection & Fallback Strategy

**Status:** ACCEPTED (2026-02-24)
**Related:** AI Quality, Cost Control

**Decision**: The default model for agent and generative tasks is `claude-sonnet-4-6`. A fallback mechanism defaults to `claude-opus-4-6` for computationally complex tasks (via `CLAUDE_MODEL_FALLBACK` or dynamically when estimated tokens > threshold).

**Rationale**: `claude-sonnet-4-6` operates at ~1/5th the cost of Opus with 90-95% of the intelligence for routine bug fixes and context lookups. Hardcoding Opus resulted in exorbitant costs per ticket (~$0.25+). We preserve Opus strictly as a high-precision fallback.

---

## ADR-014: Incremental vs Full-Repo Context Strategy

**Status:** ACCEPTED (2026-02-24)
**Related:** AI Quality, Context Management, Performance

**Decision**: The default code context strategy changes from `FULL_REPO` to `INCREMENTAL` code-fetching. `FULL_REPO` is preserved as an explicit override (via `full-repo` Jira label). Future expansion will map Context RAG to Amazon OpenSearch Serverless.

**Rationale**: Passing 2-3MB repository snapshots into the context window for a 10-line bug fix creates severe latency (minutes to parse) and noise (model hallucination on irrelevant code). The Incremental strategy uses smart graph traversal via Language Servers and Abstract Syntax Trees to fetch only dependencies mapped to the modified files.

---

## Document History

| Date | Version | Author | Change |
|------|---------|--------|--------|
| 2026-02-22 | 1.0 | Principal Engineer | Initial inline ADRs (design decisions) |
| 2026-02-24 | 1.1 | AI Colleague | Consolidated ADRs 007-011 from STRATEGY.md |
| 2026-03-21 | 2.0 | Principal Engineer | Created standalone strategic ADRs 001-007; renumbered inline ADRs 008-014 |

---

## Guidelines for Adding ADRs

1. **Use template** from [ADR-000](./ADR-000-template.md)
2. **Status**: PROPOSED -> ACCEPTED -> SUPERSEDED (if needed)
3. **Immutability**: Once ACCEPTED, treat as historical record
4. **Link code**: Reference relevant source files in "Implementation" section
5. **Include examples**: Show before/after code patterns
6. **Add metrics**: Document how to measure success
7. **Strategic decisions**: Create standalone `.md` file in this directory
8. **Tactical decisions**: Add inline to this README

See [ADR-000-template.md](./ADR-000-template.md) for full template.
