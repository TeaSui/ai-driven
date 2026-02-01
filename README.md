# AI-Driven Development System

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
| AI Engine         | Claude AI (direct API, `claude-sonnet-4-20250514`) |
| Orchestration     | AWS Step Functions (Standard)  |
| Code Context      | AWS S3 (14-day lifecycle)      |
| State Management  | AWS DynamoDB (single-table)    |
| Compute           | AWS Lambda (Java 21)           |
| API Gateway       | AWS API Gateway                |
| Secrets           | AWS Secrets Manager            |
| Infrastructure    | AWS CDK (TypeScript)           |

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
  tests/                          # E2E / integration tests (TypeScript)
  docs/                           # Documentation
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

All secrets are managed through AWS Secrets Manager:

| Secret | Purpose |
|--------|---------|
| `ai-driven/claude-api-key` | Claude API authentication |
| `ai-driven/bitbucket-credentials` | Bitbucket app password |
| `ai-driven/jira-credentials` | Jira API token |

## Jira Labels

| Label         | Effect                                    |
|---------------|-------------------------------------------|
| `ai-generate` | Triggers the AI code generation workflow  |
| `ai-test`     | Enables dry-run mode (skips PR creation)  |
| `dry-run`     | Alternative dry-run label                 |
| `test-mode`   | Alternative test mode label               |

## Documentation

- [Detailed Workflow Design](docs/ai-code-gen-workflow-detailed.md)
- [Test Coverage Analysis](docs/test-coverage-gap-analysis.md)
- [Next Phase Roadmap](docs/next-phase-roadmap.md)

## License

MIT
