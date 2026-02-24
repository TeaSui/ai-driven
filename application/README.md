# Application — Java 21 Gradle Multi-Module

The backend application containing all Lambda handler implementations, domain client interfaces, and shared libraries.

## Module Structure

```
application/
  core/                   # Shared foundation (interfaces, models, config, agent framework)
  jira-client/            # IssueTrackerClient → Jira REST API
  bitbucket-client/       # SourceControlClient → Bitbucket REST API
  github-client/          # SourceControlClient → GitHub REST API
  claude-client/          # Claude AI direct API client (auto-continuation + tool use)
  lambda-handlers/        # Lambda handler implementations (fat JAR)
```

### Module Dependency Graph

```
lambda-handlers
  ├── core
  ├── jira-client       → core
  ├── bitbucket-client  → core
  ├── github-client     → core
  └── claude-client     → core
```

## Modules

### `core`
Shared models, domain interfaces, utilities, and agent framework used by all other modules.

**Key packages:**
- `com.aidriven.core.model` — Domain entities (`TicketState`, `TicketInfo`, `AgentResult`, `GeneratedFile`)
- `com.aidriven.core.config` — Configuration (`AppConfig`, `ClaudeConfig`, `FetchConfig`)
- `com.aidriven.core.source` — Source control abstraction (`SourceControlClient`, `Platform`, `PlatformResolver`, `RepositoryResolver`)
- `com.aidriven.core.tracker` — Issue tracker abstraction (`IssueTrackerClient`)
- `com.aidriven.core.context` — Code context strategy (`ContextStrategy`)
- `com.aidriven.core.repository` — DynamoDB repositories (`TicketStateRepository`, `GenerationMetricsRepository`)
- `com.aidriven.core.service` — AWS service wrappers (`SecretsService`, `ContextStorageService`, `IdempotencyService`)
- `com.aidriven.core.exception` — Typed exceptions (`AiDrivenException`, `ExternalServiceException`)
- `com.aidriven.core.util` — Utilities (`LambdaCorrelationContext`, `LambdaInputValidator`, `SourceFileFilter`, `OutputSanitizer`)
- `com.aidriven.core.agent` — Agent orchestration (`AgentOrchestrator`, `CommentIntentClassifier`)
- `com.aidriven.core.agent.tool` — Tool provider pattern (`ToolProvider`, `ToolRegistry`, `*ToolProvider` implementations)

### `jira-client`
Jira Cloud REST API client implementing `IssueTrackerClient`.
- `JiraClient` — Fetch ticket, add comments, transition status, extract labels

### `bitbucket-client`
Bitbucket Cloud REST API client implementing `SourceControlClient`.
- `BitbucketClient` — Download repo archive, create branches, commit files, create PRs, search files

### `github-client`
GitHub REST API v3 client implementing `SourceControlClient`.
- `GitHubClient` — Same operations as Bitbucket client via GitHub's tree/commit API

### `claude-client`
Direct Claude API client (not AWS Bedrock).
- `ClaudeClient` — Send prompts, handle auto-continuation for truncated responses, tool-use support
- Configurable: model, max tokens, temperature via constructor params

### `lambda-handlers`
AWS Lambda handler implementations, packaged as a single fat JAR.

| Handler | Mode | Purpose |
|---------|------|---------|
| `JiraWebhookHandler` | Pipeline | API Gateway entry point; validates Jira webhooks, starts Step Functions |
| `FetchTicketHandler` | Pipeline | Fetches full ticket details from Jira |
| `CodeFetchHandler` | Pipeline | Downloads repo, filters source files, stores context in S3 |
| `ClaudeInvokeHandler` | Pipeline | Reads S3 context, invokes Claude, parses JSON response |
| `PrCreatorHandler` | Pipeline | Creates branch, commits generated files, opens PR |
| `MergeWaitHandler` | Pipeline | Manages task-token callback for PR merge wait |
| `AgentWebhookHandler` | Agent | Validates comment webhooks, invokes AgentOrchestrator |

**Key classes:**
- `ServiceFactory` — Singleton factory for all service instantiation (client creation, tool registry, context services)
- `ContextService` — Orchestrates smart vs full-repo context strategies

**Handler patterns:**
- Dual-constructor pattern (no-arg for Lambda, package-private for testing)
- MDC correlation context (`correlationId`, `ticketKey`, `handler`) on every invocation
- Configurable via environment variables with fallback defaults

## Core Design Patterns

### Interface + Factory (Multi-Platform Support)

```
SourceControlClient (interface)     IssueTrackerClient (interface)
├── BitbucketClient                 └── JiraClient
└── GitHubClient

ServiceFactory.getSourceControlClient(platform) → resolves implementation
```

### ToolProvider + ToolRegistry (Agent Mode)

```
ToolProvider (interface)
├── SourceControlToolProvider  → wraps SourceControlClient
├── IssueTrackerToolProvider   → wraps IssueTrackerClient
├── CodeContextToolProvider    → wraps ContextService
├── MonitoringToolProvider     → wraps MonitoringClient
├── MessagingToolProvider      → wraps MessagingClient
└── DataToolProvider           → wraps DataClient

ToolRegistry
├── register(ToolProvider)     → register provider by namespace
├── getAvailableTools(ticket)  → filtered tool definitions for Claude
└── execute(ToolCall)          → routes to correct provider by namespace
```

## Build Commands

```bash
# Build fat JAR (all handlers in one artifact)
./gradlew clean build

# Run unit tests
./gradlew test

# Build without tests
./gradlew build -x test

# List all tasks
./gradlew tasks
```

## Dependencies

| Library | Purpose |
|---------|---------|
| AWS SDK v2 | DynamoDB, Secrets Manager, S3, Step Functions |
| Lombok | Boilerplate reduction (`@Slf4j`, `@Builder`) |
| Jackson | JSON serialization/deserialization |
| Apache Commons | String utilities |
| JUnit 5 | Testing framework |
| Mockito | Mocking in unit tests |

## Build Output

The `lambda-handlers` module produces a fat JAR at:
```
lambda-handlers/build/libs/lambda-handlers-all.jar
```
This single JAR is deployed to all Lambda functions, with each function pointing to a different handler class.
