# ADR-008: All-in Spring Boot Migration & Core Module Decomposition

## Status: Accepted

## Date: 2026-03-21

## Context

The system has evolved from a Lambda-first architecture (ADR-001, ADR-002) to a hybrid
Lambda + Spring Boot design (ADR-007). With `spring-boot-app` now serving as the sole entry point,
we are carrying technical debt:

1. **AppConfig monolith (71 fields)** — bridges `AppProperties` records back to a flat
   Lombok builder class. Every consumer takes the full config surface.
2. **Core module (28 packages, 123+ classes)** — mixes agent orchestration, persistence,
   security, AST parsing, and infrastructure concerns. Difficult to test in isolation.
3. **Interfaces in wrong module** — `SourceControlClient`, `IssueTrackerClient`, `AiClient`
   live in `core`, forcing clients to depend on core's 28-package surface area.
4. **Spring AI workarounds** — `core/build.gradle` excludes 5+ Spring modules to use
   Spring AI "library-only". In a full Spring Boot context these excludes are unnecessary.

## Decision

### 1. Remove AppConfig — Use AppProperties Directly

Delete `AppConfig` and its sub-config classes (`ClaudeConfig`, `AgentConfig`, `FetchConfig`,
`JiraConfig`, `BitbucketConfig`). Replace all usages with Spring Boot's type-safe
`AppProperties` records, injected via constructor.

**Rationale:** `CoreServiceConfig.appConfig()` is a 60-line bridge that maps `AppProperties`
→ `AppConfig`. This bridge adds zero value now that the application has moved to Spring Boot. `AppProperties`
already provides the exact decomposition we wanted (nested records per concern).

### 2. Move Client Interfaces to SPI

Move from `core` to `spi`:
- `com.aidriven.core.agent.AiClient` → `com.aidriven.spi.client.AiClient`
- `com.aidriven.core.source.SourceControlClient` → `com.aidriven.spi.client.SourceControlClient`
- `com.aidriven.core.tracker.IssueTrackerClient` → `com.aidriven.spi.client.IssueTrackerClient`
- Related: `RepositoryReader`, `RepositoryWriter`, `Platform`, `ToolProvider`

**Rationale:** Clients (jira-client, github-client, claude-client) currently depend on `core`
just to implement these interfaces. Moving to `spi` means clients only need `spi` as compile
dependency, reducing coupling surface from 123 classes to ~15.

### 3. Split Core Module

Split `core` (28 packages) into focused modules:

| Module | Contains | Dependencies |
|--------|----------|-------------|
| `core-model` | Domain models, exceptions, config value objects | spi only |
| `core-agent` | AgentOrchestrator, ToolRegistry, guardrails, swarm | spi, core-model |
| `core-infra` | DynamoDB repos, security, resilience, cache, observability | spi, core-model, AWS SDK |
| `core-context` | AST parsing, context strategies, file scanning | spi, core-model, JavaParser |

### 4. Remove Spring AI Excludes

With all-in Spring Boot, remove the 5+ excludes from `core/build.gradle` and
`mcp-bridge/build.gradle`. Let Spring Boot's dependency management handle versions.

## Consequences

### Positive
- `core-agent` can be unit-tested with zero AWS dependencies
- Clients depend only on `spi` (15 classes vs 123)
- Spring Boot fat JAR unchanged (same total deps, just better organized)
- `AppProperties` is the single source of truth for configuration
- No more bridge code mapping between config representations

### Negative
- Multi-module Gradle increases initial build complexity
- Existing tests must update imports (mechanical, can be scripted)
- Short-term velocity hit during migration

### Migration Path
1. Move interfaces to SPI (no behavior change)
2. Delete AppConfig, replace with AppProperties across core
3. Split core into sub-modules
4. Remove Spring AI excludes
5. Clean up dead code
