# AI-Driven Development System

> **Current Status:**
> **Completed:** Priority 1 (Reliability), Priority 2 (AI Quality)
> **In Progress:** Priority 3 (Platform Expansion) — GitHub support, multi-repo
> **Next:** Priority 5 (Conversational Agent Mode)
> **Roadmap:** [docs/next-phase-roadmap.md](docs/next-phase-roadmap.md)

An intelligent automation platform that transforms Jira tickets into production-ready code through AI-powered agents. The system operates in two modes:

**Pipeline Mode** — Automated end-to-end: a Jira label triggers code generation, PR creation, and merge wait. Deterministic, single-shot workflow via AWS Step Functions.

**Agent Mode** (planned) — Interactive: developers chat with AI via Jira comments. The AI dynamically selects tools (source control, monitoring, messaging) to investigate issues, write fixes, open PRs, and coordinate with the team. Multi-turn, multi-actor conversations. See [impl-15](docs/impl/impl-15-agent-mode.md).

## Architecture

### Pipeline Mode (Current)

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
        ├── 2. FetchCode      — Download repo → store context in S3
        ├── 3. ClaudeInvoke   — Read S3 context → call Claude API → parse response
        ├── 4. CreatePR       — Create branch, commit files, open PR
        └── 5. WaitForMerge   — Task-token callback (up to 7 days)
                                    │
                                    ▼
                          Developer reviews & merges PR
                                    │
                                    ▼
                  Source Control Webhook → MergeWaitHandler → Workflow Complete
```

### Agent Mode (Planned — [impl-15](docs/impl/impl-15-agent-mode.md))

```
Developer comments "@ai investigate the root cause" on Jira ticket
        │
        ▼
Jira Comment Webhook → AgentWebhookHandler (Lambda)
        │
        ▼
    AgentOrchestrator:
        │
        ├── Load conversation history (DynamoDB)
        ├── Classify comment intent
        ├── Call Claude with ToolProvider definitions
        ├── Execute tool calls via ToolRegistry → ToolProviders
        └── Post response as Jira comment
                │
                ▼
    Developer replies → next turn (loop)
```

### Technology Stack

| Component         | Technology                     |
|-------------------|--------------------------------|
| Issue Tracking    | Jira Cloud (label + comment triggers) |
| Source Control    | Bitbucket Cloud, GitHub (REST APIs) |
| AI Engine         | Claude AI (direct API, `claude-opus-4-6`) |
| Orchestration     | AWS Step Functions (pipeline), Lambda (agent) |
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
    core/                         # Shared models, interfaces, config, agent framework
      agent/                      # AgentOrchestrator, CommentIntentClassifier (planned)
      agent/tool/                 # ToolProvider, ToolRegistry, *ToolProvider impls (planned)
      source/                     # SourceControlClient interface, PlatformResolver
      tracker/                    # IssueTrackerClient interface
      context/                    # ContextStrategy interface
    jira-client/                  # IssueTrackerClient → Jira REST API
    bitbucket-client/             # SourceControlClient → Bitbucket REST API
    github-client/                # SourceControlClient → GitHub REST API
    claude-client/                # Claude AI client (auto-continuation + tool use)
    lambda-handlers/              # Lambda entry points + ServiceFactory + fat JAR
  infrastructure/                 # AWS CDK (TypeScript)
    lib/ai-driven-stack.ts        # Full stack definition
  docs/                           # Documentation + implementation specs
    impl/                         # Numbered implementation documents (impl-01 to impl-15)
    architecture.md               # System architecture overview
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
| `ai-driven/github-credentials` | GitHub personal access token |
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
| `DEFAULT_PLATFORM` | `BITBUCKET` | Default source control platform |
| `CONTEXT_MODE` | `FULL_REPO` | Context strategy (`FULL_REPO` or `INCREMENTAL`) |

## Jira Labels

| Label         | Effect                                    |
|---------------|-------------------------------------------|
| `ai-generate` | Triggers the AI code generation pipeline  |
| `ai-agent`    | Enables agent mode (comment-based interaction) |
| `ai-test`     | Enables dry-run mode (skips PR creation)  |
| `platform:github` | Route to GitHub (default: Bitbucket) |
| `platform:bitbucket` | Explicit Bitbucket routing |
| `repo:owner/name` | Override target repository |
| `tool:monitoring` | Enable monitoring tools in agent mode |
| `tool:messaging` | Enable messaging tools in agent mode |
| `tool:data` | Enable data tools in agent mode |
| `full-repo` | Force full repository context |
| `smart-context` | Force incremental/smart context |

## Documentation

- [Architecture Overview](docs/architecture.md)
- [Detailed Workflow Design](docs/README.md)
- [Next Phase Roadmap](docs/next-phase-roadmap.md)
- [Implementation Documents](docs/impl/) (impl-01 to impl-15)
- [Test Cases](tests/test-cases.md)
- [Test Coverage Analysis](tests/test-coverage-gap-analysis.md)
- [Application README](application/README.md)
- [Infrastructure README](infrastructure/README.md)

## License

MIT
