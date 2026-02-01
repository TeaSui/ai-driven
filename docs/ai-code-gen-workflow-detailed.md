# AI Code Generation Workflow — Detailed Design

## Overview

The AI-Driven system automates code generation by processing Jira tickets through a linear Step Functions workflow. When a developer adds an `ai-generate` label to a Jira ticket, the system automatically generates code and creates a pull request for review.

## High-Level Flow

```
1. Jira Webhook          → JiraWebhookHandler validates + starts workflow
2. Fetch Ticket Details   → FetchTicketHandler gets full Jira ticket info
3. Fetch Code Context     → BitbucketFetchHandler downloads repo → S3
4. Generate Code          → ClaudeInvokeHandler calls Claude API with context
5. Create Pull Request    → PrCreatorHandler commits code + opens PR
6. Wait for Merge         → MergeWaitHandler uses task-token callback pattern
```

## Lambda Handlers

| Handler | Trigger | Purpose |
|---------|---------|---------|
| `JiraWebhookHandler` | API Gateway POST `/jira-webhook` | Validate webhook, idempotency check, start Step Functions |
| `FetchTicketHandler` | Step Functions invoke | Fetch ticket details from Jira API, determine agent type |
| `BitbucketFetchHandler` | Step Functions invoke | Download full repository archive → filter → upload to S3 |
| `ClaudeInvokeHandler` | Step Functions invoke | Read S3 context, build prompt, call Claude API, parse response |
| `PrCreatorHandler` | Step Functions invoke | Create branch, commit files, open PR in Bitbucket |
| `MergeWaitHandler` | Step Functions (task token) + API Gateway POST `/merge-webhook` | Store task token, handle merge callback |

## Step Functions Workflow

```
┌──────────────────────────────────────────────────────────────────┐
│  ai-driven-linear-workflow                                       │
│                                                                  │
│  FetchTicket → FetchCode → ClaudeInvoke → CreatePR → DryRunCheck │
│                                                        │         │
│                                             ┌──────────┘         │
│                                             │                    │
│                                ┌──── dryRun=true → SUCCESS       │
│                                │                                 │
│                                └── otherwise → WaitForMerge      │
│                                                   │              │
│                                               SUCCESS            │
└──────────────────────────────────────────────────────────────────┘
```

**Retries**: All tasks retry on `Lambda.ServiceException`, `Lambda.AWSLambdaException`, `Lambda.SdkClientException`, and `States.TaskFailed` (max 3 retries, 5s initial interval, 2x backoff). Claude invocation uses max 2 retries (cost consideration).

**Timeouts**: Overall workflow: 7 days (to accommodate PR review time). Individual Lambdas range from 1 min (webhook) to 15 min (Claude invocation).

## Code Context Strategy

`BitbucketFetchHandler` downloads the **entire repository** as a zip archive from Bitbucket to Lambda's `/tmp` (2GB ephemeral storage). It then:
1. Extracts all files
2. Filters out non-source files (images, builds, `node_modules/`, `.git/`, etc.)
3. Applies a per-file size cap (100KB) and total context cap (700K characters)
4. Concatenates and uploads to S3 under `context/{executionId}/code-context.txt`

This avoids the Step Functions 256KB payload limit while giving Claude comprehensive project context.

## Claude AI Integration

The system uses **direct Claude API calls** (not AWS Bedrock):
- Model: `claude-sonnet-4-20250514`
- Max tokens: 8,192
- Temperature: 0.2 (low for deterministic code generation)
- Auto-continuation: If response is truncated (`stop_reason: max_tokens`), the handler automatically sends a continuation request

The prompt instructs Claude to respond with a strict JSON format containing file paths, contents, operations, commit message, PR title, and PR description.

## DynamoDB Schema (Single-Table Design)

| Entity | PK | SK | Purpose |
|--------|----|----|---------|
| Ticket State | `TICKET#{ticketId}` | `STATE` | Processing status, agent type, error info |
| Idempotency | `IDEMPOTENT#{eventId}` | `CHECK` | Prevent duplicate webhook processing |
| Task Token | `PR#{normalizedPrUrl}` | `TOKEN` | Store Step Functions task tokens for merge callbacks |

## API Gateway Endpoints

| Path | Method | Handler | Purpose |
|------|--------|---------|---------|
| `/jira-webhook` | POST | `JiraWebhookHandler` | Entry point for Jira events |
| `/merge-webhook` | POST | `MergeWaitHandler` | Callback for Bitbucket merge events |

## Error Handling

- **Idempotency**: DynamoDB-based deduplication using `webhookId` from Jira events
- **Rate Limiting**: Exponential backoff on Claude API 429 responses
- **Typed Exceptions**: Custom exceptions (`AiDrivenException`, `ExternalServiceException`) with structured logging
- **Step Functions Catches**: All tasks catch failures and route to a `Fail` state with error details

## Security

- All credentials stored in **AWS Secrets Manager** (Claude API key, Bitbucket credentials, Jira API token)
- **No plaintext credentials** in code or configuration files
- S3 bucket uses server-side encryption (S3-managed keys)
- API Gateway rate limiting (100 rps, 200 burst)
- DynamoDB Point-in-Time Recovery enabled

## Build & Deploy

```bash
# Build fat JAR (all handlers in one JAR)
cd application && ./gradlew clean build

# Synthesize CloudFormation template
cd infrastructure && npx cdk synth

# Deploy to AWS
cd infrastructure && npx cdk deploy
```
