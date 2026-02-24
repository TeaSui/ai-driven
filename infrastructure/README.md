# Infrastructure — AWS CDK (TypeScript)

AWS Cloud Development Kit stack defining all infrastructure for the AI-Driven Development System.

## Stack Overview

All resources are defined in a single stack: [`lib/ai-driven-stack.ts`](lib/ai-driven-stack.ts)

```
AiDrivenStack
  ├── Secrets Manager (3 secrets)
  ├── DynamoDB Table (single-table design)
  ├── S3 Bucket (code context storage)
  ├── IAM Roles (3 least-privilege roles)
  ├── Lambda Functions (6 handlers)
  ├── Step Functions State Machine (linear workflow)
  ├── API Gateway (2 webhook endpoints)
  ├── CloudWatch Dashboard (8 metric widgets)
  └── CloudFormation Outputs
```

## Resources

### Secrets Manager
| Secret | Purpose |
|--------|---------|
| `ai-driven/claude-api-key` | Claude API key |
| `ai-driven/bitbucket-credentials` | Bitbucket app password |
| `ai-driven/github-credentials` | GitHub personal access token |
| `ai-driven/jira-credentials` | Jira API token |

### DynamoDB
- Table: `ai-driven-state`
- Partition key: `PK` (String), Sort key: `SK` (String)
- GSI: `GSI1` (for secondary access patterns)
- Billing: Pay-per-request
- TTL: `ttl` attribute
- Point-in-time recovery: enabled

### S3
- Bucket: `ai-driven-code-context-{accountId}`
- Lifecycle: 14-day expiration for `context/` prefix
- Encryption: S3-managed keys
- Public access: blocked

### Lambda Functions
All functions use Java 21, share a common environment config, and have X-Ray tracing enabled.

| Function | Memory | Timeout | Role |
|----------|--------|---------|------|
| `ai-driven-jira-webhook` | 512 MB | 1 min | JiraWebhookRole |
| `ai-driven-fetch-ticket` | 512 MB | 5 min | ProcessingRole |
| `ai-driven-bitbucket-fetch` | 2048 MB | 10 min | ProcessingRole |
| `ai-driven-claude-invoke` | 2048 MB | 15 min | ProcessingRole |
| `ai-driven-pr-creator` | 512 MB | 5 min | ProcessingRole |
| `ai-driven-merge-wait` | 512 MB | 1 min | MergeWaitRole |

### Step Functions
- State machine: `ai-driven-linear-workflow`
- Workflow: FetchTicket → FetchCode → ClaudeInvoke → CreatePR → DryRunCheck → WaitForMerge
- X-Ray tracing: enabled
- Logging: ALL level to CloudWatch Logs
- Timeout: configurable via `MERGE_WAIT_TIMEOUT_DAYS` (default: 7 days)

### API Gateway
| Endpoint | Handler | Mode | Purpose |
|----------|---------|------|---------|
| POST `/jira-webhook` | JiraWebhookHandler | Pipeline | Jira event entry point |
| POST `/merge-webhook` | MergeWaitHandler | Pipeline | Source control merge callback |
| POST `/agent/webhook` | AgentWebhookHandler | Agent | Jira comment entry point |

Rate limiting: 100 rps / 200 burst

### CloudWatch Dashboard
Dashboard name: `ai-driven-operations`

| Row | Widgets |
|-----|---------|
| 1 | Lambda Invocations, Lambda Errors |
| 2 | Lambda Duration (P95), Lambda Throttles |
| 3 | Workflow Executions, Workflow Duration |
| 4 | DynamoDB Read/Write Capacity, Workflow Failures (24h) |

## Environment Variables

All Lambda functions share these environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `DYNAMODB_TABLE_NAME` | *(from stack)* | DynamoDB state table name |
| `CLAUDE_SECRET_ARN` | *(from stack)* | Claude API key secret ARN |
| `BITBUCKET_SECRET_ARN` | *(from stack)* | Bitbucket credentials secret ARN |
| `JIRA_SECRET_ARN` | *(from stack)* | Jira credentials secret ARN |
| `CODE_CONTEXT_BUCKET` | *(from stack)* | S3 bucket for code context |
| `MAX_FILE_SIZE_CHARS` | `100000` | Per-file char limit |
| `MAX_TOTAL_CONTEXT_CHARS` | `3000000` | Total context char limit |
| `MAX_FILE_SIZE_BYTES` | `500000` | Skip files over this size |
| `MAX_CONTEXT_FOR_CLAUDE` | `700000` | Claude context char limit |
| `CLAUDE_MODEL` | `claude-opus-4-6` | Claude model ID |
| `CLAUDE_MAX_TOKENS` | `32768` | Claude max output tokens |
| `CLAUDE_TEMPERATURE` | `0.2` | Claude temperature |
| `MERGE_WAIT_TIMEOUT_DAYS` | `7` | Merge wait timeout |

## Commands

```bash
# Install dependencies
npm install

# Synthesize CloudFormation template
npx cdk synth

# Deploy to AWS
npx cdk deploy

# Diff with deployed stack
npx cdk diff

# Destroy all resources
npx cdk destroy
```

## Prerequisites

- Node.js 18+
- AWS CDK CLI (`npm install -g aws-cdk`)
- AWS credentials configured (`aws configure`)
- Application fat JAR built (`cd ../application && ./gradlew clean build`)
