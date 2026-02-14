# Documentation

Detailed design documentation, test analysis, and implementation specifications for the AI-Driven Development System.

## Contents

| Document | Description |
|----------|-------------|
| [README.md](README.md) | This file — docs index |
| [next-phase-roadmap.md](next-phase-roadmap.md) | Progress tracker linking to all implementation docs |
| [test-cases.md](../tests/test-cases.md) | Comprehensive test case definitions |
| [test-coverage-gap-analysis.md](../tests/test-coverage-gap-analysis.md) | Analysis of missing test coverage |

## Implementation Documents

All implementation specs live in [`impl/`](impl/) and are tracked in the [roadmap](next-phase-roadmap.md).

### Priority 1 — Reliability & Observability ✅

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
| [impl-06](impl/impl-06-incremental-context.md) | Incremental Context | 🔲 To Do |
| [impl-07](impl/impl-07-multi-model-support.md) | Multi-Model Support | 🔲 To Do |
| [impl-08](impl/impl-08-prompt-feedback-loop.md) | Prompt Iteration Feedback Loop | 🔲 To Do |

### Priority 3 — Platform Expansion

| # | Title | Status |
|---|-------|--------|
| [impl-09](impl/impl-09-github-support.md) | GitHub Support | 🔲 To Do |
| [impl-10](impl/impl-10-multi-repo-support.md) | Multi-Repository Support | 🔲 To Do |
| [impl-11](impl/impl-11-issue-tracker-integration.md) | Linear / Notion / Shortcut Integration | 🔲 To Do |

### Priority 4 — Cost & Security

| # | Title | Status |
|---|-------|--------|
| [impl-12](impl/impl-12-cost-controls.md) | Cost Controls | 🔲 To Do |
| [impl-13](impl/impl-13-audit-trail.md) | Audit Trail | 🔲 To Do |
| [impl-14](impl/impl-14-input-sanitization.md) | Input Sanitization | 🔲 To Do |

## Workflow Design

### High-Level Flow

```
1. Jira Webhook          → JiraWebhookHandler validates + starts workflow
2. Fetch Ticket Details   → FetchTicketHandler gets full Jira ticket info
3. Fetch Code Context     → BitbucketFetchHandler downloads repo → S3
4. Generate Code          → ClaudeInvokeHandler calls Claude API with context
5. Create Pull Request    → PrCreatorHandler commits code + opens PR
6. Wait for Merge         → MergeWaitHandler uses task-token callback pattern
```

### Lambda Handlers

| Handler | Trigger | Purpose |
|---------|---------|---------| 
| `JiraWebhookHandler` | API Gateway POST `/jira-webhook` | Validate webhook, idempotency check, start Step Functions |
| `FetchTicketHandler` | Step Functions invoke | Fetch ticket details from Jira API, determine agent type |
| `BitbucketFetchHandler` | Step Functions invoke | Download full repository archive → filter → upload to S3 |
| `ClaudeInvokeHandler` | Step Functions invoke | Read S3 context, build prompt, call Claude API, parse response |
| `PrCreatorHandler` | Step Functions invoke | Create branch, commit files, open PR in Bitbucket |
| `MergeWaitHandler` | Step Functions (task token) + API Gateway POST `/merge-webhook` | Store task token, handle merge callback |

### DynamoDB Schema (Single-Table Design)

| Entity | PK | SK | Purpose |
|--------|----|----|---------| 
| Ticket State | `TICKET#{ticketId}` | `STATE` | Processing status, agent type, error info |
| Idempotency | `IDEMPOTENT#{eventId}` | `CHECK` | Prevent duplicate webhook processing |
| Task Token | `PR#{normalizedPrUrl}` | `TOKEN` | Store Step Functions task tokens for merge callbacks |

### Code Context Strategy

`BitbucketFetchHandler` downloads the **entire repository** as a zip archive to Lambda's `/tmp` (2GB ephemeral storage), then:
1. Extracts all files
2. Filters out non-source files (images, builds, `node_modules/`, `.git/`, etc.)
3. Applies per-file size cap and total context cap (configurable via env vars)
4. Concatenates and uploads to S3 under `context/{executionId}/code-context.txt`

### Claude AI Integration

- Model: configurable via `CLAUDE_MODEL` env var (default: `claude-opus-4-6`)
- Max tokens: configurable via `CLAUDE_MAX_TOKENS` (default: 32768)
- Temperature: configurable via `CLAUDE_TEMPERATURE` (default: 0.2)
- Auto-continuation: truncated responses are automatically continued (max 5 continuations)
- Prompt: instructs Claude to respond in strict JSON format with file paths, contents, commit message, PR title, and PR description

### Observability

- **MDC Correlation IDs**: Every log line includes `correlationId`, `ticketKey`, and `handler` via SLF4J MDC
- **CloudWatch Dashboard**: `ai-driven-operations` with Lambda metrics, Step Functions metrics, and DynamoDB capacity
- **AWS X-Ray**: Active tracing on all Lambda functions and Step Functions state machine
