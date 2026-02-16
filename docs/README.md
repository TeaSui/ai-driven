# Documentation

Detailed design documentation, test analysis, and implementation specifications for the AI-Driven Development System.

## Contents

| Document | Description |
|----------|-------------|
| [README.md](README.md) | This file — docs index |
| [architecture.md](architecture.md) | System architecture, design patterns, and integration strategy |
| [next-phase-roadmap.md](next-phase-roadmap.md) | Progress tracker linking to all implementation docs |
| [test-cases.md](../tests/test-cases.md) | Comprehensive test case definitions |
| [test-coverage-gap-analysis.md](../tests/test-coverage-gap-analysis.md) | Analysis of missing test coverage |

## Implementation Documents

All implementation specs live in [`impl/`](impl/) and are tracked in the [roadmap](next-phase-roadmap.md).

Implementation documents are **immutable once completed** — they serve as a historical decision log. New strategies and changes are captured in new impl documents rather than retroactively editing completed ones.

### Priority 1 — Reliability & Observability

| # | Title | Status |
|---|-------|--------|
| [impl-01](impl/impl-01-config-externalization.md) | Configuration Externalization | ✅ Complete |
| [impl-02](impl/impl-02-mdc-correlation-ids.md) | MDC Correlation IDs | ✅ Complete |
| [impl-03](impl/impl-03-constructor-standardization.md) | Constructor Standardization | ✅ Complete |
| [impl-04](impl/impl-04-cloudwatch-dashboards.md) | CloudWatch Dashboards | ✅ Complete |
| [impl-05](impl/impl-05-xray-tracing.md) | AWS X-Ray Tracing | ✅ Complete |

### Priority 2 — AI Quality

| # | Title | Status |
|---|-------|--------|
| [impl-06](impl/impl-06-incremental-context.md) | Incremental Context | ✅ Complete |
| [impl-07](impl/impl-07-multi-model-support.md) | Multi-Model Support | ✅ Complete |
| [impl-08](impl/impl-08-prompt-feedback-loop.md) | Prompt Iteration Feedback Loop | ✅ Complete |

### Priority 3 — Platform Expansion

| # | Title | Status |
|---|-------|--------|
| [impl-09](impl/impl-09-github-support.md) | GitHub Support | ✅ Done |
| [impl-10](impl/impl-10-multi-repo-support.md) | Multi-Repository Support | 🔲 To Do |
| [impl-11](impl/impl-11-issue-tracker-integration.md) | Linear / Notion / Shortcut Integration | 🔲 To Do |

### Priority 4 — Cost & Security

| # | Title | Status |
|---|-------|--------|
| [impl-12](impl/impl-12-cost-controls.md) | Cost Controls | 🔲 To Do |
| [impl-13](impl/impl-13-audit-trail.md) | Audit Trail | 🔲 To Do |
| [impl-14](impl/impl-14-input-sanitization.md) | Input Sanitization | 🔲 To Do |

### Priority 5 — Conversational Agent

| # | Title | Status |
|---|-------|--------|
| [impl-15](impl/impl-15-agent-mode.md) | Agent Mode (Jira-as-Chat) | 🔲 To Do |

## Workflow Design

### High-Level Flow (Pipeline Mode)

```
1. Jira Webhook          → JiraWebhookHandler validates + starts workflow
2. Fetch Ticket Details   → FetchTicketHandler gets full Jira ticket info
3. Fetch Code Context     → CodeFetchHandler downloads repo → S3
4. Generate Code          → ClaudeInvokeHandler calls Claude API with context
5. Create Pull Request    → PrCreatorHandler commits code + opens PR
6. Wait for Merge         → MergeWaitHandler uses task-token callback pattern
```

### High-Level Flow (Agent Mode — Planned)

```
1. Jira Comment Webhook  → AgentWebhookHandler validates + classifies intent
2. Load Conversation     → ConversationRepository loads DynamoDB history
3. Select Tools          → ToolRegistry filters ToolProviders by ticket config
4. Claude + Tool Loop    → AgentOrchestrator runs agentic loop (max N turns)
5. Post Response         → JiraCommentFormatter posts result as Jira comment
```

### Lambda Handlers

| Handler | Trigger | Mode | Purpose |
|---------|---------|------|---------|
| `JiraWebhookHandler` | API Gateway POST `/jira-webhook` | Pipeline | Validate webhook, idempotency check, start Step Functions |
| `FetchTicketHandler` | Step Functions invoke | Pipeline | Fetch ticket details from Jira API, determine agent type |
| `CodeFetchHandler` | Step Functions invoke | Pipeline | Download repository archive → filter → upload to S3 |
| `ClaudeInvokeHandler` | Step Functions invoke | Pipeline | Read S3 context, build prompt, call Claude API, parse response |
| `PrCreatorHandler` | Step Functions invoke | Pipeline | Create branch, commit files, open PR |
| `MergeWaitHandler` | Step Functions (task token) + API Gateway | Pipeline | Store task token, handle merge callback |
| `AgentWebhookHandler` | API Gateway POST `/agent/webhook` | Agent | Validate comment webhook, invoke AgentOrchestrator |

### DynamoDB Schema (Single-Table Design)

| Entity | PK | SK | Purpose |
|--------|----|----|---------|
| Ticket State | `TICKET#{ticketId}` | `STATE` | Processing status, agent type, error info |
| Idempotency | `IDEMPOTENT#{eventId}` | `CHECK` | Prevent duplicate webhook processing |
| Task Token | `PR#{normalizedPrUrl}` | `TOKEN` | Store Step Functions task tokens for merge callbacks |
| Conversation | `AGENT#{ticketKey}` | `MSG#{timestamp}#{seq}` | Agent mode conversation history (planned) |

### Key Design Patterns

| Pattern | Where | Purpose |
|---------|-------|---------|
| Interface + Factory | `SourceControlClient`, `IssueTrackerClient` | Multi-platform support (Bitbucket, GitHub, Jira, etc.) |
| Strategy | `ContextStrategy` (`SmartContext`, `FullRepo`) | Swappable code context generation |
| ToolProvider + ToolRegistry | Agent mode tool system (planned) | Extensible tool integration with typed dispatch |
| Platform/Repository Resolver | `PlatformResolver`, `RepositoryResolver` | Auto-detect platform and repo from ticket metadata |
| Single-Table DynamoDB | All state entities | Efficient access patterns with minimal tables |
| Task-Token Callback | `MergeWaitHandler` | Async wait for PR merge (up to 7 days) |

### Observability

- **MDC Correlation IDs**: Every log line includes `correlationId`, `ticketKey`, and `handler` via SLF4J MDC
- **CloudWatch Dashboard**: `ai-driven-operations` with Lambda metrics, Step Functions metrics, and DynamoDB capacity
- **AWS X-Ray**: Active tracing on all Lambda functions and Step Functions state machine
