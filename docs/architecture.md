# Architecture Overview

This document describes the system architecture, design decisions, and integration strategy for the AI-Driven Development System.

---

## System Context

The AI-Driven Development System sits between issue trackers, source control platforms, AI services, and communication tools. It automates code generation, investigation, and team coordination.

```
┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│   Jira      │   │  Bitbucket  │   │   GitHub    │   │   Slack     │
│  (Tracker)  │   │  (Source)   │   │  (Source)   │   │  (Comms)    │
└──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
       │                 │                 │                 │
       │    webhooks     │    REST API     │    REST API     │  REST API
       ▼                 ▼                 ▼                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    AI-Driven Development System                      │
│                                                                      │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────────┐  │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────────┐  │
│  │  Pipeline   │  │  Agent Mode  │  │     Shared Infrastructure  │  │
│  │  Mode       │  │  (planned)   │  │                            │  │
│  │             │  │  SQS FIFO    │  │  DynamoDB  S3  Secrets Mgr  │  │
│  │  Step Fns   │  │  Orchestrator│  │  CloudWatch  X-Ray         │  │
│  └─────────────┘  └──────────────┘  └────────────────────────────┘  │
│                                                                      │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │                    Domain Client Layer                         │   │
│  │  SourceControlClient  IssueTrackerClient  ClaudeClient        │   │
│  │  MonitoringClient     MessagingClient     DataClient (future) │   │
│  └───────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │   Claude AI     │
                    │  (Anthropic API)│
                    └─────────────────┘
```

---

## Operating Modes

The system supports two complementary modes that coexist on shared infrastructure.

### Pipeline Mode (Current)

Deterministic, single-shot workflow triggered by a Jira label. Best for well-defined tickets where the expected output is code generation + PR.

```
Trigger: Jira label "ai-generate" → issue_updated webhook
Flow:    Step Functions linear workflow (5 handlers)
Output:  Pull request with generated code
Wait:    Task-token callback for PR merge (up to 7 days)
```

**Characteristics:** Predictable, auditable, no conversation state, single Claude invocation.

### Agent Mode (Planned — [impl-15](impl/impl-15-agent-mode.md))

Interactive, multi-turn workflow triggered by Jira comments. Best for investigation, debugging, and collaborative development where the AI needs to dynamically choose actions.

```
Trigger: Jira comment "@ai ..." → comment_created webhook
Flow:    AgentWebhookHandler (validate + ack comment + enqueue)
         → SQS FIFO (MessageGroupId = ticketKey)
         → AgentProcessorHandler → AgentOrchestrator
         → Claude with tool-use → ToolRegistry → repeat
Output:  Jira ack comment updated in-place with progress and final results
State:   Conversation history per ticket in DynamoDB
```

**Characteristics:** Dynamic tool selection, multi-turn memory, multi-actor support, agentic loop, FIFO ordering per ticket, wall-clock circuit breaker (12 min), immediate ack comment with progress updates.

### Mode Selection

| Event | Condition | Mode |
|-------|-----------|------|
| `jira:issue_updated` | Status change + `ai-generate` label | Pipeline |
| `jira:comment_created` | `ai-agent` label + `@ai` prefix | Agent |

Both modes share the same domain client layer, DynamoDB table, and infrastructure.

---

## Domain Client Architecture

All external integrations follow the same pattern: a typed interface in `core`, implementations in dedicated Gradle modules, and resolution via `ServiceFactory`.

```
                    ┌──────────────────┐
                    │  ServiceFactory   │
                    │  (Singleton)      │
                    └───────┬──────────┘
                            │ creates
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
   SourceControlClient  IssueTrackerClient  ClaudeClient
   (interface)          (interface)          (concrete)
     │        │              │
     │        │              └── JiraClient
     │        │
     │        └── GitHubClient
     └── BitbucketClient
```

### Adding a New Integration

Every integration follows this pattern:

```
1. Define interface in core/  (typed methods, domain-specific)
2. Create Gradle module       (client implementation)
3. Register in ServiceFactory (lazy singleton, secrets-based init)
4. Create ToolProvider         (agent mode: expose as Claude tools)
5. Register in ToolRegistry    (namespace-based routing)
```

### Platform Resolution

The system auto-detects which source control platform and repository to use:

```
PlatformResolver priority:
  1. Jira label: "platform:github" → GITHUB
  2. Repository URL in ticket → parse domain
  3. DEFAULT_PLATFORM env var → fallback
  4. BITBUCKET → ultimate default

RepositoryResolver priority:
  1. Jira label: "repo:owner/name" → explicit
  2. Repository URL in ticket → parse path
  3. Default owner/repo env vars → fallback
```

---

## Tool Provider Pattern (Agent Mode)

The agent mode tool system bridges typed domain clients to Claude's tool-use API through the `ToolProvider` + `ToolRegistry` pattern.

```
┌──────────────────────────────────────────────────────┐
│                   ToolRegistry                        │
│                                                      │
│  register(provider)        → add by namespace        │
│  getAvailableTools(ticket) → filtered tool schemas   │
│  execute(toolCall)         → route + truncate output  │
└──────────────┬───────────────────────────────────────┘
               │ routes to
   ┌───────────┼───────────┬───────────┐
   ▼           ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│ source   │ │ issue    │ │ monitor  │ │ message  │
│ _control │ │ _tracker │ │ ing      │ │ ing      │
│          │ │          │ │          │ │          │
│ ToolProv │ │ ToolProv │ │ ToolProv │ │ ToolProv │
└────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
     │            │            │            │
     ▼            ▼            ▼            ▼
 SourceControl IssueTracker Monitoring  Messaging
 Client        Client       Client      Client
 (typed)       (typed)      (typed)     (typed)
```

### Design Rationale

| Alternative | Why Not |
|-------------|---------|
| Monolithic `ToolExecutor` switch | Grows linearly, violates Open/Closed, single point of coupling |
| Generic `ToolAdapter` with `Map<String, Object>` | Loses compile-time type safety, runtime casting errors |
| MCP servers (now) | Protocol overhead for internal use; no multi-LLM requirement yet |
| **Hybrid ToolProvider (chosen)** | Type-safe domain calls + unified registration + MCP migration path |

### Tool Output Truncation

Each `ToolProvider` declares a `maxOutputChars()` contract (default: 20,000 chars). The `ToolRegistry` enforces truncation after dispatch to prevent context window overflow. Domain-specific truncation strategies apply — log providers return first/last N lines, query providers return first 50 rows + schema summary.

### MCP Migration Path

Each `ToolProvider` maps 1:1 to a future MCP server:

```
ToolProvider.namespace()       → MCP server name
ToolProvider.toolDefinitions() → MCP tool schemas
ToolProvider.execute()         → MCP tool handler
ToolProvider.maxOutputChars()  → MCP transport config
```

Migration trigger: when external teams need to contribute tools, or when supporting non-Claude LLM providers.

---

## Data Architecture

### DynamoDB Single-Table Design

All entities share one table with partition key `PK` and sort key `SK`.

```
┌──────────────────────┬──────────────────┬──────────────────────────────┐
│ PK                   │ SK               │ Purpose                      │
├──────────────────────┼──────────────────┼──────────────────────────────┤
│ TICKET#{ticketId}    │ STATE            │ Pipeline processing state    │
│ IDEMPOTENT#{eventId} │ CHECK            │ Webhook deduplication        │
│ PR#{normalizedUrl}   │ TOKEN            │ Step Functions task token    │
│ AGENT#{ticketKey}    │ MSG#{ts}#{seq}   │ Agent conversation (planned) │
│ METRICS#{ticketKey}  │ GEN#{timestamp}  │ Generation metrics           │
└──────────────────────┴──────────────────┴──────────────────────────────┘

GSI1: Secondary access patterns (e.g., query by correlation ID)
TTL:  Auto-cleanup for temporary state (idempotency: 24h, conversation: 30d)
```

### S3 Code Context Storage

```
s3://ai-driven-code-context-{accountId}/
  context/{executionId}/code-context.txt    ← concatenated source files
  Lifecycle: 14-day auto-expiration
```

---

## Infrastructure Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                          AWS Account                                  │
│                                                                      │
│  ┌─────────────────┐         ┌──────────────────────────────┐       │
│  │  API Gateway     │         │  Step Functions              │       │
│  │  /jira-webhook   │────────▶│  ai-driven-linear-workflow   │       │
│  │  /merge-webhook  │         │                              │       │
│  │  /agent/webhook  │──┐      │  Fetch → Context → Claude    │       │
│  └─────────────────┘  │      │  → PR → Wait                 │       │
│                        │      └──────────────────────────────┘       │
│                        │                     │                        │
│                        ▼                     ▼                        │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │                Lambda Functions (Java 21)                    │     │
│  │                                                              │     │
│  │  Pipeline:  JiraWebhook │ FetchTicket │ CodeFetch            │     │
│  │             ClaudeInvoke │ PrCreator  │ MergeWait            │     │
│  │                                                              │     │
│  │  Agent:     AgentWebhook (thin: validate + ack + enqueue)    │     │
│  │             AgentProcessor (heavy: orchestrator + tools)      │     │
│  └────────────────────────────────────────────────────────────┘     │
│           │              │              │                             │
│           ▼              ▼              ▼                             │
│  ┌──────────────┐ ┌──────────┐ ┌────────────────┐ ┌──────────────┐ │
│  │  DynamoDB    │ │  S3      │ │ Secrets Manager │ │ SQS FIFO     │ │
│  │  (state)     │ │ (context)│ │ (credentials)   │ │ (agent queue)│ │
│  └──────────────┘ └──────────┘ └────────────────┘ └──────────────┘ │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Observability                                                │   │
│  │  CloudWatch Dashboard │ X-Ray Tracing │ MDC Correlation       │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

**Agent Mode Request Flow:**

```
Jira Comment → API Gateway → AgentWebhookHandler (< 1 min)
  1. Validate webhook + classify intent
  2. Post ack comment: "🤖 Processing your request..."
  3. Enqueue to SQS FIFO (MessageGroupId = ticketKey)

SQS FIFO → AgentProcessorHandler (up to 15 min)
  1. Load conversation history from DynamoDB
  2. Run AgentOrchestrator agentic loop (Claude + tools)
  3. Update ack comment in-place with progress per tool
  4. Wall-clock circuit breaker at 12 min → post partial results
  5. Persist conversation state to DynamoDB
```

### IAM Role Strategy

| Role | Handlers | Permissions |
|------|----------|-------------|
| JiraWebhookRole | JiraWebhookHandler | Start Step Functions, DynamoDB R/W, Secrets read |
| ProcessingRole | Fetch, Context, Claude, PR | DynamoDB R/W, S3 R/W, Secrets read |
| MergeWaitRole | MergeWaitHandler | DynamoDB R/W, Step Functions SendTaskSuccess |
| AgentWebhookRole (planned) | AgentWebhookHandler | Jira API (post ack comment), SQS SendMessage |
| AgentProcessorRole (planned) | AgentProcessorHandler | DynamoDB R/W, Secrets read, S3 R/W, SQS ReceiveMessage/DeleteMessage, all client APIs (source control, monitoring, messaging) |

---

## Evolution Timeline

```
Priority 1: Reliability ────────── impl-01 to impl-05 ✅
  Config externalization, correlation IDs, dashboards, tracing

Priority 2: AI Quality ─────────── impl-06 to impl-08 ✅
  Incremental context, multi-model, prompt feedback loop

Priority 3: Platform Expansion ──── impl-09 to impl-11 (in progress)
  GitHub support, multi-repo, alternative issue trackers

Priority 4: Cost & Security ─────── impl-12 to impl-14 (planned)
  Cost controls, audit trail, input sanitization

Priority 5: Agent Mode ──────────── impl-15 (planned)
  Phase 1: Single-turn agent (comment → tools → comment)
  Phase 2: Multi-turn conversation (DynamoDB state)
  Phase 3: Multi-actor collaboration (feedback loops)
  Phase 4: External integrations (Datadog, Slack, Databricks)
```

---

## Key Architectural Decisions

| Decision | Rationale |
|----------|-----------|
| **Monolithic fat JAR** over microservices | All handlers share clients; Lambda execution context reuse makes shared singletons efficient |
| **Step Functions** over SQS chains | Visual workflow, built-in retry/error handling, task-token callback for async wait |
| **Single DynamoDB table** over multiple tables | Fewer resources, GSI covers access patterns, TTL for cleanup |
| **Direct Claude API** over AWS Bedrock | Lower latency, immediate access to latest models, auto-continuation support |
| **Interface + Factory** over dependency injection | Lambda cold start optimization — no DI framework overhead |
| **ToolProvider pattern** over MCP (for now) | Type safety, no protocol overhead; MCP migration path preserved |
| **SQS FIFO** for agent mode ordering | Guarantees per-ticket FIFO processing; prevents race conditions from rapid comments; decouples thin webhook handler from heavy processor |
| **Lambda + circuit breaker** over Step Functions Express for agent loop | Claude tool-use requires full conversation history in-memory; Step Functions Express has 256KB payload limit; wall-clock check at 12 min persists state and self-continues |
| **Impl docs as immutable history** | Decision log; new strategies in new docs, not retroactive edits |
