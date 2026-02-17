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
  tool-source-control/    # SourceControlToolProvider (agent tool)
  tool-issue-tracker/     # IssueTrackerToolProvider (agent tool)
  tool-code-context/      # CodeContextToolProvider + ContextService
  mcp-bridge/             # MCP protocol bridge for external tool servers
  service-registry/       # Platform-agnostic service wiring + multi-tenant support
  lambda-handlers/        # Lambda handler implementations (fat JAR)
```

### Module Dependency Graph

```
lambda-handlers
  ├── service-registry    → core + all clients + tools + mcp-bridge
  ├── core
  └── (Lambda-specific: SFN, SQS clients)

service-registry
  ├── core
  ├── jira-client         → core
  ├── bitbucket-client    → core
  ├── github-client       → core
  ├── claude-client       → core
  ├── tool-source-control → core
  ├── tool-issue-tracker  → core
  ├── tool-code-context   → core
  └── mcp-bridge          → core
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
- `com.aidriven.core.exception` — Typed exceptions (`HttpClientException`, `ConflictException`)
- `com.aidriven.core.util` — Utilities (`SourceFileFilter`, `JsonRepairService`, `HttpResponseHandler`)
- `com.aidriven.core.agent` — Agent orchestration (`AgentOrchestrator`, `CommentIntentClassifier`)
- `com.aidriven.core.agent.tool` — Tool provider pattern (`ToolProvider`, `ToolRegistry`)
- `com.aidriven.core.agent.guardrail` — Risk-based guardrails (`GuardedToolRegistry`, `ToolRiskRegistry`)

### `service-registry` (NEW)
Platform-agnostic service wiring with multi-tenant support.

**Key classes:**
- `ServiceRegistry` — Interface defining all service access points
- `AwsServiceRegistry` — AWS-native implementation (DynamoDB, S3, Secrets Manager)
- `TenantContext` — Multi-tenant configuration holder

This module decouples service creation from any specific runtime (Lambda, Spring Boot, etc.).
Each deployment target provides its own wiring while reusing the same `ServiceRegistry` interface.

### `jira-client`
Jira Cloud REST API client implementing `IssueTrackerClient`.
- `JiraClient` — Fetch ticket, add comments, edit comments, transition status, extract labels

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

### `mcp-bridge`
Model Context Protocol bridge for external tool servers.
- `McpBridgeToolProvider` — Bridges any MCP server into our ToolProvider contract
- `McpConnectionFactory` — Creates MCP client connections (stdio/HTTP transports)

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
| `AgentWebhookHandler` | Agent | Validates comment webhooks, enqueues to SQS FIFO |
| `AgentProcessorHandler` | Agent | Consumes SQS tasks, runs AgentOrchestrator |

**Key classes:**
- `ServiceFactory` — Lambda-specific singleton that delegates to `AwsServiceRegistry`

## Core Design Patterns

### Interface + Factory (Multi-Platform Support)

```
SourceControlClient (interface)     IssueTrackerClient (interface)
├── BitbucketClient                 └── JiraClient
└── GitHubClient

ServiceRegistry.getSourceControlClient(platform) → resolves implementation
```

### ServiceRegistry (Multi-Tenant / Multi-Runtime)

```
ServiceRegistry (interface)
├── AwsServiceRegistry     → DynamoDB, S3, Secrets Manager (production)
└── (future) LocalServiceRegistry → In-memory, file-based (development)

ServiceFactory (Lambda-specific)
└── delegates to AwsServiceRegistry + adds SFN/SQS clients
```

### ToolProvider + ToolRegistry (Agent Mode)

```
ToolProvider (interface)
├── SourceControlToolProvider  → wraps SourceControlClient
├── IssueTrackerToolProvider   → wraps IssueTrackerClient
├── CodeContextToolProvider    → wraps ContextService
├── McpBridgeToolProvider      → wraps any MCP server
└── (future) custom providers per tenant

ToolRegistry
├── register(ToolProvider)     → register provider by namespace
├── getAvailableTools(ticket)  → filtered tool definitions for Claude
└── execute(ToolCall)          → routes to correct provider by namespace
```

## Build Commands

```bash
# Build fat JAR (all handlers in one artifact)
./gradlew clean build

# Build specific module only
./gradlew :service-registry:build
./gradlew :core:build

# Run unit tests
./gradlew test

# Run tests for a specific module
./gradlew :service-registry:test

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
| MCP Java SDK | Model Context Protocol client |
| JUnit 5 | Testing framework |
| Mockito | Mocking in unit tests |

## Build Output

The `lambda-handlers` module produces a fat JAR at:
```
lambda-handlers/build/libs/lambda-handlers-all.jar
```
This single JAR is deployed to all Lambda functions, with each function pointing to a different handler class.

Individual modules can also be published as separate JARs for microservice deployments:
```
service-registry/build/libs/service-registry.jar
core/build/libs/core.jar
jira-client/build/libs/jira-client.jar
```
