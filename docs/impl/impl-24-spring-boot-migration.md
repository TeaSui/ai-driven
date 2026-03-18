# impl-24: Spring Boot + ECS Fargate Migration

## Status: PROPOSED

## Summary

Migrate the ai-driven platform from AWS Lambda + ServiceFactory to **Spring Boot 3.5 on ECS Fargate** with full Spring AI integration. This eliminates Lambda cold-start constraints, enables streaming responses, and unlocks the complete Spring AI ecosystem (ChatClient, Advisors, MCP auto-config, RAG pipeline).

---

## Current vs Target Architecture

```
CURRENT                                TARGET
─────────                              ──────
Lambda Handlers (10)                   Spring Boot App (ECS Fargate)
  ├─ ServiceFactory (39 beans)           ├─ @Configuration classes (7)
  ├─ API Gateway (webhooks)              ├─ REST Controllers (5)
  ├─ SQS Consumer (Lambda)              ├─ SQS Listeners (2)
  ├─ Step Functions (Lambda tasks)       ├─ Step Functions (HTTP tasks)
  └─ Spring AI (library-only)            └─ Spring AI (full: ChatClient + Advisors)
```

---

## Phase 1: Foundation — New Spring Boot Module (Week 1)

### 1.1 Create `spring-boot-app` Module

New Spring Boot application module as the primary application entry point.

```
application/
  spring-boot-app/          ← NEW
    src/main/java/
      com/aidriven/app/
        AiDrivenApplication.java          @SpringBootApplication
        config/
          AwsConfig.java                  @Configuration — 5 AWS SDK clients
          ExternalClientConfig.java       @Configuration — AI, SCM, Jira clients
          AgentConfig.java                @Configuration — orchestrators, swarm
          McpConfig.java                  @Configuration — MCP auto-config
          SecurityConfig.java             @Configuration — HMAC webhook validation
          ObservabilityConfig.java        @Configuration — Actuator + Micrometer
          AppProperties.java              @ConfigurationProperties
    src/main/resources/
      application.yml
    build.gradle
```

### 1.2 build.gradle

```gradle
plugins {
    id 'org.springframework.boot' version '3.5.0'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'java'
}

dependencies {
    implementation project(':core')
    implementation project(':spi')
    implementation project(':claude-client')
    implementation project(':jira-client')
    implementation project(':github-client')
    implementation project(':bitbucket-client')
    implementation project(':mcp-bridge')
    implementation project(':tool-code-context')
    implementation project(':tool-source-control')
    implementation project(':tool-issue-tracker')
    implementation project(':tool-observability')

    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-security'

    // Spring AI (full, not library-only)
    implementation "org.springframework.ai:spring-ai-starter-model-anthropic"
    implementation "org.springframework.ai:spring-ai-mcp-client-spring-boot-starter"

    // AWS
    implementation platform("software.amazon.awssdk:bom:${awsSdkVersion}")
    implementation 'software.amazon.awssdk:sqs'
    implementation 'software.amazon.awssdk:dynamodb'
    implementation 'software.amazon.awssdk:s3'
    implementation 'software.amazon.awssdk:sfn'
    implementation 'software.amazon.awssdk:secretsmanager'

    // Messaging (SQS listener)
    implementation 'io.awspring.cloud:spring-cloud-aws-starter-sqs:3.3.0'
}
```

### 1.3 application.yml

```yaml
spring:
  application:
    name: ai-driven
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-6
          max-tokens: 32768
          temperature: 0.2
    mcp:
      client:
        enabled: true

ai-driven:
  jira:
    secret-arn: ${JIRA_SECRET_ARN}
    webhook-secret-arn: ${JIRA_WEBHOOK_SECRET_ARN}
  github:
    secret-arn: ${GITHUB_SECRET_ARN}
    webhook-secret-arn: ${GITHUB_AGENT_WEBHOOK_SECRET_ARN}
  bitbucket:
    secret-arn: ${BITBUCKET_SECRET_ARN}
  claude:
    secret-arn: ${CLAUDE_SECRET_ARN}
    model: ${CLAUDE_MODEL:claude-sonnet-4-6}
    max-tokens: ${CLAUDE_MAX_TOKENS:32768}
    temperature: ${CLAUDE_TEMPERATURE:0.2}
    researcher-model: ${CLAUDE_RESEARCHER_MODEL:claude-haiku-4-5}
  aws:
    region: ${AWS_REGION:ap-southeast-1}
    sqs:
      agent-queue-url: ${AGENT_QUEUE_URL}
    dynamodb:
      state-table: ${STATE_TABLE_NAME:ai-driven-state}
    s3:
      context-bucket: ${CONTEXT_BUCKET_NAME:ai-driven-context}
    step-functions:
      state-machine-arn: ${STATE_MACHINE_ARN}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  health:
    dynamodb:
      enabled: true
```

---

## Phase 2: ServiceFactory → @Configuration (Week 1-2)

Map 39 singletons across 5 factories to 7 `@Configuration` classes.

### 2.1 Bean Mapping

| Factory | Config Class | Beans |
|---------|-------------|-------|
| `AwsClientFactory` | `AwsConfig` | DynamoDbClient, S3Client, SqsClient, SfnClient, SecretsManagerClient |
| `ExternalClientFactory` | `ExternalClientConfig` | JiraClient, GitHubClient, BitbucketClient, AiClient (4 variants), CircuitBreakers |
| `AgentSubsystemFactory` | `AgentConfig` | ConversationWindowManager, CostTracker, ApprovalStore, GuardedToolRegistry, SwarmOrchestrator, AgentOrchestrator |
| `McpProviderFactory` | `McpConfig` | McpConnectionFactory, McpGatewayClients, ManagedMcpToolProvider |
| `ServiceFactory` (direct) | `CoreServiceConfig` | SecretsService, IdempotencyService, AuditService, ContextStorageService, Repositories |
| — | `SecurityConfig` | WebhookValidator, HMAC filters |
| — | `ObservabilityConfig` | Actuator, Micrometer, CloudWatch |

### 2.2 Example: AwsConfig

```java
@Configuration
public class AwsConfig {

    @Bean
    DynamoDbClient dynamoDbClient(@Value("${ai-driven.aws.region}") String region) {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    S3Client s3Client(@Value("${ai-driven.aws.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    // ... SqsClient, SfnClient, SecretsManagerClient
}
```

### 2.3 AppConfig Bridge

Replace static `AppConfig.getInstance()` with `@ConfigurationProperties`:

```java
@ConfigurationProperties(prefix = "ai-driven")
public record AppProperties(
    JiraProperties jira,
    GitHubProperties github,
    BitbucketProperties bitbucket,
    ClaudeProperties claude,
    AwsProperties aws
) {
    public record ClaudeProperties(String secretArn, String model, int maxTokens,
                                    double temperature, String researcherModel) {}
    // ...
}
```

---

## Phase 3: Lambda Handlers → Controllers + Listeners (Week 2-3)

### 3.1 Handler Mapping

| Lambda Handler | Spring Component | Type |
|---|---|---|
| `JiraWebhookHandler` | `JiraWebhookController` | `@RestController` |
| `AgentWebhookHandler` | `AgentWebhookController` | `@RestController` |
| `HealthCheckHandler` | `HealthController` | `@RestController` (or Actuator `/health`) |
| `ApprovalHandler` | `ApprovalController` | `@RestController` |
| `AgentProcessorHandler` | `AgentSqsListener` | `@SqsListener` |
| `MergeWaitHandler` | `MergeWaitSqsListener` | `@SqsListener` |
| `FetchTicketHandler` | `PipelineService.fetchTicket()` | `@Service` method |
| `CodeFetchHandler` | `PipelineService.fetchContext()` | `@Service` method |
| `ClaudeInvokeHandler` | `PipelineService.invokeAi()` | `@Service` method |
| `PrCreatorHandler` | `PipelineService.createPr()` | `@Service` method |

### 3.2 Example: AgentWebhookController

```java
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class AgentWebhookController {

    private final WebhookValidator webhookValidator;
    private final SqsClient sqsClient;
    private final AppProperties properties;

    @PostMapping("/jira/agent")
    public ResponseEntity<Map<String, String>> handleJiraAgent(
            @RequestBody String body,
            @RequestHeader("X-Webhook-Token") String token) {
        webhookValidator.validateJiraToken(token);
        // ... classify, enqueue to SQS
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @PostMapping("/github/agent")
    public ResponseEntity<Map<String, String>> handleGitHubAgent(
            @RequestBody String body,
            @RequestHeader("X-Hub-Signature-256") String signature) {
        webhookValidator.validateGitHubHmac(body, signature);
        // ... classify, enqueue to SQS
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }
}
```

### 3.3 Example: AgentSqsListener

```java
@Component
@RequiredArgsConstructor
public class AgentSqsListener {

    private final SwarmOrchestrator swarmOrchestrator;
    private final AgentOrchestrator agentOrchestrator;

    @SqsListener("${ai-driven.aws.sqs.agent-queue-url}")
    public void processAgentTask(AgentTask task) {
        // Same logic as current AgentProcessorHandler.handleRequest()
        // but with injected dependencies instead of ServiceFactory
    }
}
```

---

## Phase 4: Spring AI Full Activation (Week 3)

### 4.1 ChatClient + Advisors

Replace manual `AnthropicChatModel` usage with `ChatClient`:

```java
@Configuration
public class SpringAiConfig {

    @Bean
    ChatClient chatClient(ChatModel chatModel,
                          ToolCallbackProvider mcpTools,
                          ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory)
                        .chatMemoryRetrieveSize(20)
                        .build(),
                    new SimpleLoggerAdvisor()
                )
                .defaultToolCallbacks(mcpTools)
                .build();
    }

    @Bean
    ChatMemory chatMemory(DynamoChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
```

### 4.2 Streaming Responses

```java
@Service
public class StreamingAgentService {

    private final ChatClient chatClient;

    public Flux<String> streamResponse(String systemPrompt, String userMessage) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .stream()
                .content();
    }
}
```

### 4.3 MCP Auto-Configuration

Replace custom `McpProviderFactory` + `McpConnectionFactory` + `McpBridgeToolProvider` with:

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers:
            jira:
              command: java
              args: ["-jar", "mcp-server-jira.jar"]
            github:
              command: java
              args: ["-jar", "mcp-server-github.jar"]
```

### 4.4 Structured Output

Replace manual JSON parsing in `ClaudeInvokeHandler` with:

```java
record GeneratedCode(List<GeneratedFile> files, String summary) {}

GeneratedCode result = chatClient.prompt()
    .system(systemPrompt)
    .user(ticketDescription)
    .call()
    .entity(GeneratedCode.class);
```

---

## Phase 5: Spring Security — Webhook Validation (Week 3)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/webhooks/**").permitAll()  // validated by HMAC filter
                .anyRequest().authenticated()
            )
            .addFilterBefore(jiraWebhookFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(githubWebhookFilter(), UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    HmacWebhookFilter githubWebhookFilter() {
        return new HmacWebhookFilter("/webhooks/github/**", "X-Hub-Signature-256", githubSecret);
    }
}
```

---

## Phase 6: Infrastructure — CDK Changes (Week 4)

### 6.1 New CDK Constructs

```typescript
// ECS Fargate Service
const fargateService = new ecs_patterns.ApplicationLoadBalancedFargateService(this, 'AiDrivenService', {
  cluster,
  taskImageOptions: {
    image: ecs.ContainerImage.fromAsset('../application/spring-boot-app'),
    containerPort: 8080,
    environment: {
      SPRING_PROFILES_ACTIVE: 'prod',
      JIRA_SECRET_ARN: jiraSecret.secretArn,
      GITHUB_SECRET_ARN: githubSecret.secretArn,
      // ...
    },
  },
  cpu: 1024,
  memoryLimitMiB: 2048,
  desiredCount: 2,
  healthCheck: { command: ['CMD-SHELL', 'curl -f http://localhost:8080/actuator/health || exit 1'] },
});

// ALB target for webhooks (replacing API Gateway)
fargateService.targetGroup.configureHealthCheck({
  path: '/actuator/health',
  interval: Duration.seconds(30),
});
```

### 6.2 Step Functions Integration

Change Lambda tasks to HTTP tasks pointing to Fargate:

```typescript
// Before: LambdaInvoke
const fetchTicket = new tasks.LambdaInvoke(this, 'FetchTicket', {
  lambdaFunction: fetchTicketFn,
});

// After: HttpInvoke via ALB
const fetchTicket = new tasks.HttpInvoke(this, 'FetchTicket', {
  apiRoot: sfnHttpEndpoint,
  apiEndpoint: sfn.TaskInput.fromText('/pipeline/fetch-ticket'),
  method: sfn.HttpMethod.POST,
  body: sfn.TaskInput.fromJsonPathAt('$'),
});
```

### 6.3 What Gets Removed

| Resource | Status |
|----------|--------|
| 9 Lambda functions | Removed |
| API Gateway | Replaced by ALB |
| Lambda IAM roles | Replaced by ECS task role |
| Lambda log groups | Replaced by ECS log groups |

### 6.4 What Stays

| Resource | Status |
|----------|--------|
| Step Functions state machine | Kept (HTTP tasks) |
| SQS FIFO queue | Kept (Fargate consumes) |
| DynamoDB tables | Kept |
| S3 bucket | Kept |
| Secrets Manager | Kept |
| CloudWatch dashboards | Updated for ECS metrics |

---

## Phase 7: Observability — Actuator + Micrometer (Week 4)

Replace custom EMF metrics with Spring Boot Actuator:

```java
@Component
@RequiredArgsConstructor
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    public void recordAgentTurn(String ticketKey, int tokens, long latencyMs) {
        meterRegistry.counter("agent.turns", "ticket", ticketKey).increment();
        meterRegistry.gauge("agent.tokens", tokens);
        meterRegistry.timer("agent.latency").record(latencyMs, TimeUnit.MILLISECONDS);
    }
}
```

Export to CloudWatch via `micrometer-registry-cloudwatch2`.

---

## Phase 8: Cutover and Cleanup (Week 5)

### 8.1 Rollback Strategy

| Component | Rollback |
|-----------|----------|
| Webhooks | DNS-weighted routing: shift traffic back to API Gateway |
| SQS | Re-enable Lambda trigger, disable Fargate consumer |
| Step Functions | Revert task definitions from HTTP to Lambda |

### 8.2 Cleanup After Validation

- Archive `lambda-handlers` module (legacy)
- Remove Lambda functions from CDK
- Replace API Gateway with ALB in CDK
- Archive `ServiceFactory` and sub-factories
- Update all documentation to reference spring-boot-app

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Fargate cold start (container pull) | Pre-warm with min 2 tasks, use ECR image cache |
| SQS message loss during migration | Run both Lambda + Fargate consumers temporarily |
| Step Functions HTTP task failures | Feature flag to switch between Lambda and HTTP tasks |
| Spring Boot startup time | Profile with Spring AOT, exclude unused auto-configs |
| Cost increase (Fargate vs Lambda) | Right-size tasks (1 vCPU / 2GB), auto-scaling policy |

---

## Timeline

| Week | Phase | Deliverable |
|------|-------|-------------|
| 1 | 1-2 | Spring Boot module, @Configuration classes, application.yml |
| 2 | 3 | Controllers, SQS listeners, PipelineService |
| 3 | 4-5 | Spring AI full activation, Spring Security |
| 4 | 6-7 | CDK Fargate, Actuator/Micrometer |
| 5 | 8 | Cutover, validation, cleanup |

---

## Dependencies

- ADR-007 (Hybrid Lambda + Fargate) → documents this decision
- impl-23 (Spring AI Adoption) → Phase 2 adapters already built
- Spring Boot 3.5+ (Java 21 support)
- Spring AI 1.1.2+ (Anthropic support, MCP auto-config)
- Spring Cloud AWS 3.3+ (SQS listener)
