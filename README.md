# AI-Driven Development System

> **🚀 Current Status:**  
> **Active Phase:** Priority 2 (AI Quality) — Implementing incremental context and multi-model support.  
> **Detailed Roadmap:** [See `docs/next-phase-roadmap.md`](docs/next-phase-roadmap.md) for full task list.

An intelligent automation platform that transforms Jira tickets into production-ready code through AI-powered agents. When a developer adds the `ai-generate` label to a Jira ticket, the system automatically fetches the ticket details, downloads the target repository for context, generates code using Claude AI, and creates a pull request in Bitbucket for human review.

## Architecture

```
Developer adds label "ai-generate" to Jira ticket
        │
        ▼
Jira Webhook → API Gateway → JiraWebhookHandler (Lambda)
        │
        ▼
    Step Functions Linear Workflow:
        │
        ├── 1. FetchTicket    — Get ticket details from Jira
        ├── 2. FetchCode      — Download full repo → store context in S3  
        ├── 3. ClaudeInvoke   — Read S3 context → call Claude API → parse response
        ├── 4. CreatePR       — Create branch, commit files, open PR in Bitbucket
        └── 5. WaitForMerge   — Task-token callback (up to 7 days)
                                    │
                                    ▼
                          Developer reviews & merges PR
                                    │
                                    ▼
                  Bitbucket Webhook → MergeWaitHandler → Workflow Complete
```

### Technology Stack

| Component         | Technology                     |
|-------------------|--------------------------------|
| Issue Tracking    | Jira Cloud (label-based trigger) |
| Source Control    | Bitbucket Cloud (REST API)     |
| AI Engine         | Claude AI (direct API, `claude-opus-4-6`) |
| Orchestration     | AWS Step Functions (Standard)  |
| Code Context      | AWS S3 (14-day lifecycle)      |
| State Management  | AWS DynamoDB (single-table)    |
| Compute           | AWS Lambda (Java 21)           |
| API Gateway       | AWS API Gateway                |
| Secrets           | AWS Secrets Manager            |
| Infrastructure    | AWS CDK (TypeScript)           |
| Observability     | CloudWatch Dashboards + AWS X-Ray |

## Project Structure

```
ai-driven/
  application/                    # Java 21 Gradle multi-module
    core/                         # Shared models, exceptions, utilities, AWS SDK clients
    jira-client/                  # Jira REST API client
    bitbucket-client/             # Bitbucket REST API client (incl. repo archive download)
    claude-client/                # Claude AI direct API client
    lambda-handlers/              # Lambda handler implementations + fat JAR
  infrastructure/                 # AWS CDK (TypeScript)
    lib/ai-driven-stack.ts        # Full stack definition
  docs/                           # Documentation + implementation specs
    impl/                         # Numbered implementation documents (impl-01 to impl-14)
    next-phase-roadmap.md         # Progress tracker with checkboxes
  tests/                          # E2E / integration tests (TypeScript)
```

## Quick Start

### Prerequisites

- Java 21 (Amazon Corretto recommended)
- Node.js 18+ (for CDK and tests)
- AWS CLI configured with appropriate credentials
- AWS CDK CLI (`npm install -g aws-cdk`)

### Build & Deploy

```bash
# Build the application (produces fat JAR for Lambda)
cd application && ./gradlew clean build

# Deploy infrastructure to AWS
cd infrastructure && npm install && npx cdk deploy
```

### Run Tests

```bash
# Java unit tests
cd application && ./gradlew test

# TypeScript integration/E2E tests
cd tests && npm install && npm test
```

## Configuration

### Secrets (AWS Secrets Manager)

| Secret | Purpose |
|--------|---------|
| `ai-driven/claude-api-key` | Claude API authentication |
| `ai-driven/bitbucket-credentials` | Bitbucket app password |
| `ai-driven/jira-credentials` | Jira API token |

### Environment Variables

All Lambda handlers share these configurable values (with sensible defaults):

| Variable | Default | Purpose |
|----------|---------|---------|
| `MAX_FILE_SIZE_CHARS` | `100000` | Max chars per source file |
| `MAX_TOTAL_CONTEXT_CHARS` | `3000000` | Total context cap (~3MB) |
| `MAX_FILE_SIZE_BYTES` | `500000` | Skip files > 500KB |
| `MAX_CONTEXT_FOR_CLAUDE` | `700000` | Max chars sent to Claude |
| `CLAUDE_MODEL` | `claude-opus-4-6` | Claude model identifier |
| `CLAUDE_MAX_TOKENS` | `32768` | Max output tokens per request |
| `CLAUDE_TEMPERATURE` | `0.2` | Model temperature |
| `MERGE_WAIT_TIMEOUT_DAYS` | `7` | PR merge wait timeout |

## Jira Labels

| Label         | Effect                                    |
|---------------|-------------------------------------------|
| `ai-generate` | Triggers the AI code generation workflow  |
| `ai-test`     | Enables dry-run mode (skips PR creation)  |
| `dry-run`     | Alternative dry-run label                 |
| `test-mode`   | Alternative test mode label               |

## Documentation

- [Detailed Workflow Design](docs/README.md)
- [Next Phase Roadmap](docs/next-phase-roadmap.md)
- [Implementation Documents](docs/impl/) (impl-01 to impl-14)
- [Test Cases](tests/test-cases.md)
- [Test Coverage Analysis](tests/test-coverage-gap-analysis.md)
- [Application README](application/README.md)
- [Infrastructure README](infrastructure/README.md)

## License

MIT
