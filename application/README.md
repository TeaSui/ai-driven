# Application — Java 21 Gradle Multi-Module

The backend application containing all Lambda handler implementations and shared libraries.

## Module Structure

```
application/
  core/                   # Shared foundation
  jira-client/            # Jira REST API client
  bitbucket-client/       # Bitbucket REST API client
  claude-client/          # Claude AI direct API client
  lambda-handlers/        # Lambda handler implementations (fat JAR)
```

### Module Dependency Graph

```
lambda-handlers
  ├── core
  ├── jira-client       → core
  ├── bitbucket-client  → core
  └── claude-client     → core
```

## Modules

### `core`
Shared models, utilities, and AWS SDK service abstractions used by all other modules.

**Key packages:**
- `com.aidriven.core.model` — Domain entities (`TicketState`, `TicketInfo`, `AgentResult`, `GeneratedFile`)
- `com.aidriven.core.repository` — DynamoDB repository (`TicketStateRepository`)
- `com.aidriven.core.service` — AWS service wrappers (`SecretsService`, `CodeContextS3Service`)
- `com.aidriven.core.exception` — Typed exceptions (`AiDrivenException`, `ExternalServiceException`)
- `com.aidriven.core.util` — Utilities (`LambdaCorrelationContext`, `LambdaInputValidator`, `SourceFileFilter`, `OutputSanitizer`)

### `jira-client`
Jira Cloud REST API client for fetching ticket details.
- `JiraClient` — Fetch ticket, extract labels, determine agent type

### `bitbucket-client`
Bitbucket Cloud REST API client for repository operations.
- `BitbucketClient` — Download repo archive, create branches, commit files, create PRs

### `claude-client`
Direct Claude API client (not AWS Bedrock).
- `ClaudeClient` — Send prompts, handle auto-continuation for truncated responses
- Configurable: model, max tokens, temperature via constructor params

### `lambda-handlers`
Six AWS Lambda handler implementations, packaged as a single fat JAR.

| Handler | Purpose |
|---------|---------|
| `JiraWebhookHandler` | API Gateway entry point; validates Jira webhooks, starts Step Functions |
| `FetchTicketHandler` | Fetches full ticket details from Jira |
| `BitbucketFetchHandler` | Downloads repo, filters source files, stores context in S3 |
| `ClaudeInvokeHandler` | Reads S3 context, invokes Claude, parses JSON response |
| `PrCreatorHandler` | Creates branch, commits generated files, opens PR |
| `MergeWaitHandler` | Manages task-token callback for PR merge wait |

**Handler patterns:**
- Dual-constructor pattern (no-arg for Lambda, package-private for testing)
- MDC correlation context (`correlationId`, `ticketKey`, `handler`) on every invocation
- Configurable via environment variables with fallback defaults

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
This single JAR is deployed to all 6 Lambda functions, with each function pointing to a different handler class.
