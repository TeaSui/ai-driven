# ADR-009: Java-to-Go Full Migration

**Status:** ACCEPTED
**Date:** 2026-04-07
**Authors:** Tea Nguyen, AI Colleague
**Related:** ADR-006 (superseded), ADR-008 (superseded), ADR-002 (superseded)

## Context and Problem Statement

The AI-driven development platform was originally built in Java 21 with Spring Boot 3.5.0, Gradle multi-module architecture, and Spring AI integration. Over Q1 2026, a parallel Go implementation was developed under `go-app/` that mirrors the Java architecture 1:1.

The Java stack imposes significant operational overhead:
- **Cold start latency**: 5-15s on ECS Fargate due to JVM + Spring Boot initialization
- **Memory footprint**: 512MB-1GB minimum, with Spring Boot overhead consuming ~200MB before application code
- **Binary size**: 59MB fat JAR with hundreds of transitive dependencies
- **P99 agent turn latency**: 25s (target: <15s) — JVM warmup is a significant contributor
- **Cost per task**: $0.15-0.25 (target: <$0.10) — idle memory reservation and slow startup inflate Lambda/Fargate costs

The Go implementation addresses all of these:
- **Cold start**: <100ms
- **Memory**: 32-64MB typical
- **Binary size**: ~15-20MB static binary (scratch container)
- **Deployment**: Single binary, no runtime dependencies

## Decision Drivers

* P99 latency target (<15s) unreachable with JVM warmup overhead
* Cost per task target (<$0.10) requires lower memory footprint and faster startup
* Go implementation already at ~85% feature parity (16K LOC across 126 files)
* Maintaining two parallel stacks (Java + Go) creates drift risk and doubles maintenance burden
* Go's concurrency model (goroutines) better fits the event-driven, multi-turn agent architecture
* Spring AI dependency adds 16MB to fat JAR but only provides a wrapper around the Anthropic HTTP API

## Considered Options

* **Option 1: Complete Go migration, remove Java**
* **Option 2: Keep Java as primary, remove Go**
* **Option 3: Maintain both stacks with feature flags**

## Decision Outcome

Chosen option: **Option 1 — Complete Go migration, remove Java**, because the Go implementation already exists at high parity, directly serves performance/cost targets, and eliminates dual-stack maintenance.

### Positive Consequences

* Cold start drops from 5-15s to <100ms (150x improvement)
* Memory footprint drops from 512MB to 64MB (8x reduction)
* Fargate costs drop proportionally (smaller task size, faster scaling)
* Single codebase eliminates drift risk
* Simpler deployment pipeline (single binary vs Docker + JVM layer)
* CDK infrastructure already Go-native (`go-app/infrastructure/`)

### Negative Consequences

* Spring AI library features (prompt caching helpers, ChatMemory) must be implemented directly against Anthropic HTTP API — already done via `claude.Client`
* JavaParser AST analysis replaced by regex-based file summarization (lower fidelity but language-agnostic)
* Existing Java ADRs (002, 006, 008) superseded — documented for historical context

## Superseded Decisions

| ADR | Title | Reason |
|-----|-------|--------|
| ADR-002 | ServiceFactory over Spring DI | Go uses explicit dependency wiring — no DI framework needed |
| ADR-006 | Spring AI Library-Only Adoption | Go uses direct Anthropic HTTP API via `claude.Client` |
| ADR-008 | All-in Spring Boot | Go uses Echo web framework + explicit module composition |

## Migration Scope

### Completed (pre-migration)
- ReAct agent orchestrator with turn-based loop
- Multi-turn conversation windowing with token budgeting
- Comment intent classification (keyword + LLM-based)
- Tool registry with namespace-based routing
- Risk-based guardrails with approval gating
- DynamoDB persistence (conversations, approvals, rate limits, ticket state)
- Claude AI integration (Bedrock + Anthropic API)
- Jira, GitHub, and Bitbucket provider clients
- SQS FIFO async task processing
- Rate limiting, circuit breaker, in-memory caching
- CloudWatch EMF metrics
- Swarm multi-agent orchestration (Coder/Reviewer/Tester/Researcher)
- CDK infrastructure (Go CDK, fully deployed)

### Completed (during migration)
- DynamoDB-backed CostTracker
- Jira comment-based ProgressTracker
- Secrets Manager resolution for all credentials
- SlackNotifier wiring for approval guardrails
- FileSummarizer (multi-language structural summarization)
- DirectoryScanner (project structure auto-discovery)
- MCP servers (Jira + GitHub)
- view_file_outline and search_grep tool implementations

### Removed
- `application/` — Java Gradle multi-module (9 modules, ~25K LOC)
- `infrastructure/` — TypeScript CDK (replaced by `go-app/infrastructure/`)
- `mcp-gateway/` — Node.js Lambda (replaced by Go MCP gateway Lambda URL)

## Metrics to Track

| Metric | Before (Java) | Target (Go) |
|--------|--------------|-------------|
| Cold start | 5-15s | <100ms |
| P99 latency | 25s | <15s |
| Memory usage | 512MB | <128MB |
| Binary size | 59MB JAR | <20MB |
| Cost per task | $0.15-0.25 | <$0.10 |
| Fargate task size | 512 CPU / 1024 MB | 256 CPU / 512 MB |
