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
  spring-boot-app/        # Spring Boot application entry point (ECS Fargate deployment)
```

### Module Dependency Graph

```
spring-boot-app
  ├── core
  ├── jira-client       → core
  ├── bitbucket-client  → core
  ├── github-client     → core
  ├── claude-client     → core
  ├── mcp-bridge        → core
  └── spi (service provider interface)
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
AI client adapters for Claude models, powered by Spring AI 1.1.2 (library-only, no Spring Boot).
- `SpringAiClientAdapter` — Default provider using Spring AI's `AnthropicChatModel` (simple chat with retry + prompt caching) and `AnthropicApi` (tool-use with raw content blocks)
- `BedrockClient` — AWS Bedrock-based client for Claude models (alternative provider)
- `ClaudeProvider` enum — Routes between `SPRING_AI` (default) and `BEDROCK`
- Configurable: model, max tokens, temperature via immutable builder pattern

### `spring-boot-app`
Spring Boot 3.5 application deployed on ECS Fargate, replacing the Lambda-based architecture.

**Key components:**
- `AiDrivenApplication.java` — Spring Boot entry point with embedded Tomcat
- `@Configuration` classes — Bean definitions for AWS clients, external integrations, agents, observability
- `@RestController` classes — REST endpoints for webhooks (Jira, GitHub) and health checks
- `@SqsListener` classes — SQS FIFO message consumers for agent tasks and merge-wait logic
- `@Service` classes — Business logic for pipeline stages (fetch ticket, generate code, create PR)

**Deployment:**
- Containerized as Docker image, deployed on ECS Fargate with ALB
- Health checks via Spring Boot Actuator (`/actuator/health`)
- Structured logging with Spring Boot defaults + custom EMF metrics
- Configuration via `application.yml` + environment variable overrides

**Handler mapping (Lambda → Spring Boot):**
- `JiraWebhookHandler` → `JiraWebhookController`
- `AgentWebhookHandler` → `AgentWebhookController`
- `AgentProcessorHandler` → `AgentSqsListener`
- `MergeWaitHandler` → `MergeWaitSqsListener`
- `FetchTicketHandler`, `CodeFetchHandler`, `ClaudeInvokeHandler`, `PrCreatorHandler` → Methods in `PipelineService`

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
# Build the entire application
./gradlew clean build

# Build spring-boot-app module only
./gradlew :spring-boot-app:build

# Run unit tests
./gradlew test

# Build without tests
./gradlew build -x test

# Build Docker image for ECS deployment
docker build -t ai-driven:latest -f application/spring-boot-app/Dockerfile application/

# List all tasks
./gradlew tasks
```

## Dependencies

| Library | Purpose |
|---------|---------|
| AWS SDK v2 | DynamoDB, Secrets Manager, S3, Step Functions |
| Spring AI 1.1.2 | Anthropic API client (prompt caching, retry, structured types) |
| Lombok | Boilerplate reduction (`@Slf4j`, `@Builder`) |
| Jackson | JSON serialization/deserialization |
| Apache Commons | String utilities |
| JUnit 5 | Testing framework |
| Mockito | Mocking in unit tests |

## Build Output

The `spring-boot-app` module produces executable artifacts for ECS Fargate deployment:
```
spring-boot-app/build/libs/spring-boot-app.jar     # Executable Spring Boot application
spring-boot-app/build/libs/spring-boot-app-sources.jar  # Source code for debugging
Dockerfile                                           # Container image definition for ECS
```
The JAR includes all dependencies and runs with `java -jar spring-boot-app.jar` on ECS Fargate.
