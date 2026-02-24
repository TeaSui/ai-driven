# Architecture Decision Records (ADRs)

> **Vision:** ADRs exist to capture the "why" behind significant architectural shifts. They prevent repeated debates and provide crucial context for future engineers maintaining this AI-driven system.

This directory contains architectural decisions for the ai-driven system. Each ADR is immutable once completed and serves as a historical design log.

Format follows [ADR Template](./ADR-000-template.md). New ADRs should be numbered sequentially (ADR-014, etc.) and follow semantic versioning if superseded.

## Index

- [ADR-001: Interface Segregation for Domain Clients](./README.md#adr-001-interface-segregation-for-domain-clients)
- [ADR-002: Value Objects for Domain Safety](./README.md#adr-002-value-objects-for-domain-safety)
- [ADR-003: Operation Context for Traceability](./README.md#adr-003-operation-context-for-traceability)
- [ADR-004: Factory Pattern for Service Instantiation](./README.md#adr-004-factory-pattern-for-service-instantiation)
- [ADR-005: Caching Strategy](./README.md#adr-005-caching-strategy)
- [ADR-006: Circuit Breaker for Resilience](./README.md#adr-006-circuit-breaker-for-resilience)
- [ADR-007: Separate webhook HMAC secret from API PAT credentials](./README.md#adr-007-separate-webhook-hmac-secret-from-api-pat-credentials)
- [ADR-008: Skip verification when secret not configured](./README.md#adr-008-skip-verification-when-secret-not-configured)
- [ADR-009: SQS FIFO for agent task ordering](./README.md#adr-009-sqs-fifo-for-agent-task-ordering)
- [ADR-010: Single shared Jira webhook token](./README.md#adr-010-single-shared-jira-webhook-token)
- [ADR-011: Single default OperationContext in MCP servers](./README.md#adr-011-single-default-operationcontext-in-mcp-servers)
- [ADR-012: Claude Model Selection & Fallback Strategy](./README.md#adr-012-claude-model-selection--fallback-strategy)
- [ADR-013: Incremental vs Full-Repo Context Strategy](./README.md#adr-013-incremental-vs-full-repo-context-strategy)

---

## ADR-001: Interface Segregation for Domain Clients

**Status:** ACCEPTED (2026-02-22)  
**Authors:** Principal Engineer  
**Related:** SOLID Principles, Interface Segregation Principle (ISP)

### Problem Statement

`SourceControlClient` is a "god interface" combining read and write operations. This violates ISP because:
- Clients that only read cannot rely on compile-time guarantees (might accidentally write)
- Testing is harder: mocks must implement full interface even for read-only tests
- Future operations (streaming, transactions) require new implementations

### Solution

Segregate into role-specific interfaces:
- `RepositoryReader` - read-only operations (queries, searches, listing)
- `RepositoryWriter` - write operations (commits, PRs, branches, comments)
- `SourceControlClient` extends both (backward compatible)

### Benefits

✅ Type-safe separation: clients that need only reads can use `RepositoryReader`  
✅ Testability: mock read and write paths independently  
✅ Extensibility: future patterns (streaming, transactions) extend single interface  
✅ Backward Compatibility: existing code using `SourceControlClient` continues working  

### Trade-offs

⚠️ More interfaces to navigate initially (mitigated by IDE support)  
⚠️ Implementation changes required (one-time effort, future-proof)  

### Implementation

1. Create `RepositoryReader` interface (no state changes)
2. Create `RepositoryWriter` interface (state-mutating operations)
3. Refactor `SourceControlClient` to extend both
4. Mark old methods `@Deprecated` for migration path
5. Update all implementations (GitHub, Bitbucket clients)

### Examples

**Before:**
```java
SourceControlClient client = getClient();
// Type system can't prevent:
client.commitFiles(...);  // oops, needed read-only access
```

**After:**
```java
RepositoryReader reader = getClient();  // Can only read
// Type system prevents:
reader.commitFiles(...);  // COMPILE ERROR! ✅

RepositoryWriter writer = getClient();  // Can read + write
writer.commitFiles(...);  // OK
```

### Monitoring & Metrics

- Deprecation warning coverage: % of codebase migrated to new interfaces
- Test split ratio: % of tests using segregated interfaces

---

## ADR-002: Value Objects for Domain Safety

**Status:** ACCEPTED (2026-02-22)  
**Authors:** Principal Engineer  
**Related:** Domain-Driven Design, Type Safety

### Problem Statement

Domain identifiers (ticket keys, branch names, repository owner/name) are represented as raw `String`. This causes:
- No validation: invalid values can propagate through the system
- No type safety: `createBranch(String branch, String owner)` could swap parameters
- Hard to understand code intent: `String` is ambiguous

### Solution

Create immutable value objects with validation:
- `TicketKey` - Jira ticket identifier (e.g., "PROJ-123")
- `RepositoryIdentifier` - owner + name pair
- `BranchName` - branch identifier
- Each is immutable, validated at creation, hashable

### Benefits

✅ Type safety: compile-time parameter checking  
✅ Validation: invalid states impossible (fail-fast at creation)  
✅ Self-documenting: intent is clear from type  
✅ Immutability: thread-safe by design  
✅ Composable: can use in collections, maps reliably  

### Trade-offs

⚠️ More classes to maintain (mitigated by standardized pattern)  
⚠️ Extra constructor calls (negligible performance impact)  

### Implementation Pattern

```java
public final class TicketKey {
    public static TicketKey of(String value) {
        validate(value);
        return new TicketKey(value);
    }
    
    private static void validate(String value) {
        if (!value.matches("^[A-Z][A-Z0-9]+-\\d+$")) {
            throw new IllegalArgumentException("Invalid ticket key: " + value);
        }
    }
}
```

### Examples

**Before:**
```java
void createBranch(String branch, String owner, String repo) {
    // No validation - could have:
    // - createBranch("from-master", "", "repo")  // invalid owner
    // - createBranch("PROJ-123", "owner", "repo") // branch mismatch
}
```

**After:**
```java
void createBranch(BranchName branch, RepositoryIdentifier repo) {
    // Compile-time safety:
    // - BranchName validates format ✅
    // - RepositoryIdentifier encapsulates owner + name ✅
    // - Can't swap parameters (type mismatch) ✅
}
```

### Monitoring & Metrics

- Value object usage coverage: % of method signatures using domain types
- Validation error rate: frequency of invalid input attempts (should be logged)

---

## ADR-003: Operation Context for Traceability

**Status:** ACCEPTED (2026-02-22)  
**Authors:** Principal Engineer  
**Related:** Observability, Distributed Tracing

### Problem Statement

Current handler signatures are verbose with repeated parameters:
```java
void handleWebhook(String ticketKey, String userId, String correlationId, 
                   String requestId, Instant timestamp, String source) 
```

This causes:
- Parameter bloat: hard to follow method intent
- Inconsistency: different handlers pass context differently
- Poor traceability: no standard way to extract trace ID
- Audit gaps: user/timestamp not consistently captured

### Solution

Create `OperationContext` value object encapsulating:
- `correlationId` - Unique trace ID (UUID)
- `ticketKey` - The ticket being operated on
- `userId` - User initiating operation
- `requestId` - Idempotency key (optional)
- `timestamp` - Operation start time
- `source` - Operation origin ("webhook", "cli", "api")

Use builder pattern for fluent construction.

### Benefits

✅ Reduced parameter bloat: one context param replaces 5+  
✅ Improved traceability: correlation ID standard across all operations  
✅ Better audit trail: user/timestamp/source captured consistently  
✅ Self-documenting: intent is clear from OperationContext type  
✅ Future-proof: can add fields (retryCount, deadline, etc.) without changing signatures  

### Trade-offs

⚠️ Additional abstraction layer (minimal complexity)  
⚠️ Small memory overhead (negligible in practice)  

### Implementation

1. Create `OperationContext` value object with builder
2. Replace TenantContext + individual params with OperationContext
3. Update all handlers and service methods
4. Ensure correlation ID is logged consistently

### Examples

**Before:**
```java
public void handleWebhook(String ticketKey, String userId, String correlationId,
                          String requestId, Instant timestamp) {
    log.info("Processing ticket {} for user {} [{}]", 
             ticketKey, userId, correlationId);
    
    serviceA.doWork(ticketKey, userId, correlationId, requestId, timestamp);
    serviceB.doWork(ticketKey, userId, correlationId, requestId, timestamp);
}
```

**After:**
```java
public void handleWebhook(OperationContext context) {
    log.info("Processing {}", context);  // clear, concise
    
    serviceA.doWork(context);  // context flows consistently
    serviceB.doWork(context);
}

// Usage in logs:
// "Processing OperationContext{correlationId='abc-123', ticket='PROJ-456'}"
```

### Monitoring & Metrics

- Correlation ID coverage: % of log lines containing correlationId
- Context propagation: trace ID consistently flows through handler chain
- Audit trail completeness: % of operations logged with user/timestamp

---

## ADR-004: Factory Pattern for Service Instantiation

**Status:** PROPOSED (Target: Q2 2026)  
**Target Completion:** Phase 1 Week 2  

**Consequences if not implemented:** 
- The system will remain rigid, requiring code modifications to `ServiceFactory` every time a new tool or client is introduced, increasing coupling and deployment risk.

### Problem Statement

`ServiceFactory` is a "god object" responsible for all service creation. Current state:
- ~400+ LOC with responsibilities scattered
- Difficult to test in isolation
- Tight coupling to AWS clients

### Proposed Solution

Extract role-specific factories:
- `ClientFactory` - creates SourceControl, IssueTracker clients
- `StorageFactory` - creates S3, DynamoDB services
- `ToolFactory` - creates and registers tool providers

### Expected Benefits

- Separation of concerns: each factory has single purpose
- Testability: can mock individual factories
- Extensibility: add new platform clients without modifying `ServiceFactory`

### Implementation Status

⏳ In progress - awaiting Phase 1 Week 2

---

## ADR-005: Caching Strategy

**Status:** PROPOSED (Target: Q2 2026)  
**Target Completion:** Phase 2

**Consequences if not implemented:** 
- The agent will unnecessarily re-fetch the same immutable repository blobs for multiple concurrent tickets, increasing API throttling risk from Bitbucket/GitHub and driving up cross-AZ data transfer costs.

### Problem Statement

Repeated calls to external APIs (SecretsManager, GitHub API, DynamoDB) without caching. Causes:
- Increased latency
- API rate limit exhaustion
- Higher AWS costs

### Proposed Solution

Implement multi-tier caching:
- L1: In-memory cache with TTL (secrets: 1h, metadata: 15m)
- L2: DynamoDB cache for cross-Lambda persistence
- Cache invalidation strategies: TTL expiration, manual flush

### Expected Benefits

- Reduced external API calls (target: 70% cache hit ratio for secrets)
- Improved latency (sub-10ms for cached reads vs. 100-500ms for API calls)
- Cost reduction (estimated 30-50% AWS spend reduction)

### Implementation Status

⏳ Planned for Phase 2

---

## ADR-006: Circuit Breaker for Resilience

**Status:** PROPOSED (Target: Q2 2026)  
**Target Completion:** Phase 2

**Consequences if not implemented:** 
- System stability will be entirely coupled to third-party endpoints (Jira, GitHub, Anthropic). A minor rate-limit spike in those services will cause cascading failures and Lambda timeouts throughout the entire pipeline.

### Problem Statement

No circuit breaker for external service failures. When Claude API is down:
- All handlers fail immediately
- No fast-fail mechanism
- Lambda timeouts increase (all retries attempted)

### Proposed Solution

Implement Hystrix-style circuit breaker:
- States: CLOSED (normal) → OPEN (fail fast) → HALF_OPEN (test) → CLOSED
- Configurable: failure threshold (5), success threshold (2), timeout (30s)

### Expected Benefits

- Fast failure when dependencies are down
- Automatic recovery testing
- Monitoring of service health

### Implementation Status

⏳ Planned for Phase 2

---

## Document History

| Date | Version | Author | Change |
|------|---------|--------|--------|
| 2026-02-22 | 1.0 | Principal Engineer | Initial ADRs: 001-003 accepted, 004-006 pending |
| 2026-02-24 | 1.1 | AI Colleague | Consolidated ADRs 007-011 from STRATEGY.md |

---

## Guidelines for Adding ADRs

1. **Use template** from [ADR-000](./ADR-000-template.md)
2. **Status**: PROPOSED → ACCEPTED → SUPERSEDED (if needed)
3. **Immutability**: Once ACCEPTED, treat as historical record
4. **Link code**: Reference relevant source files in "Implementation" section
5. **Include examples**: Show before/after code patterns
6. **Add metrics**: Document how to measure success

See [ADR-000-template.md](./ADR-000-template.md) for full template.

---

## ADR-007: Separate webhook HMAC secret from API PAT credentials

**Status:** ACCEPTED  
**Related:** Security

**Decision**: `GITHUB_AGENT_WEBHOOK_SECRET_ARN` stores a plain 40-char string. `GITHUB_SECRET_ARN` stores a JSON dictionary.

**Rationale**: HMAC secrets are raw strings; API PATs require username context. Mixing them requires JSON parsing in a security-validation hot path.

---

## ADR-008: Skip verification when secret not configured

**Status:** ACCEPTED  
**Related:** Security, Rollout Strategy

**Decision**: `WebhookValidator` WARN-logs and returns normally when expected secret is null.

**Rationale**: Enables deploy-first, configure-second rollouts. Monitored via CloudWatch alarm on the `WARN` pattern.

---

## ADR-009: SQS FIFO for agent task ordering

**Status:** ACCEPTED  
**Related:** Concurrency, Architecture

**Decision**: Agent tasks enqueue to SQS FIFO with `messageGroupId = ticketKey`.

**Rationale**: Guarantees sequential execution of multiple `@ai` comments on the same ticket, preventing context overrides.

---

## ADR-010: Single shared Jira webhook token

**Status:** ACCEPTED  
**Related:** Security

**Decision**: Pipeline and Agent components both source `JIRA_WEBHOOK_SECRET_ARN`.

**Rationale**: Streamlines webhook administration in Jira for operators.

---

## ADR-011: Single default OperationContext in MCP servers

**Status:** ACCEPTED  
**Related:** Architecture, MCP

**Decision**: Initial MCP servers default to an implicit single-tenant configuration.

**Rationale**: Multi-org capability requires HTTP/SSE header transport logic outside initial phase scope.

---

## ADR-012: Claude Model Selection & Fallback Strategy

**Status:** ACCEPTED (2026-02-24)  
**Related:** AI Quality, Cost Control

**Decision**: The default model for agent and generative tasks is `claude-sonnet-4-6`. A fallback mechanism defaults to `claude-opus-4-6` for computationally complex tasks (via `CLAUDE_MODEL_FALLBACK` or dynamically when estimated tokens > threshold).

**Rationale**: `claude-sonnet-4-6` operates at ~1/5th the cost of Opus with 90-95% of the intelligence for routine bug fixes and context lookups. Hardcoding Opus resulted in exorbitant costs per ticket (~$0.25+). We preserve Opus strictly as a high-precision fallback.

---

## ADR-013: Incremental vs Full-Repo Context Strategy

**Status:** ACCEPTED (2026-02-24)  
**Related:** AI Quality, Context Management, Performance

**Decision**: The default code context strategy changes from `FULL_REPO` to `INCREMENTAL` code-fetching. `FULL_REPO` is preserved as an explicit override (via `full-repo` Jira label). Future expansion will map Context RAG to Amazon OpenSearch Serverless.

**Rationale**: Passing 2-3MB repository snapshots into the context window for a 10-line bug fix creates severe latency (minutes to parse) and noise (model hallucination on irrelevant code). The Incremental strategy uses smart graph traversal via Language Servers and Abstract Syntax Trees to fetch only dependencies mapped to the modified files.
