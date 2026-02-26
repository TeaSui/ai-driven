package com.aidriven.lambda.factory;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.claude.ClaudeClient;
import com.aidriven.core.agent.AiClient;
import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.ConversationWindowManager;
import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.WorkflowContextProvider;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.tool.ManagedMcpToolProvider;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.config.AppConfig;
import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.cost.BudgetTracker;
import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.service.impl.CodeContextS3ServiceImpl;
import com.aidriven.core.service.impl.SecretsServiceImpl;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.resilience.CircuitBreaker;
import com.aidriven.github.GitHubClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.mcp.McpConnectionFactory;
import com.aidriven.mcp.McpGatewayClient;
import com.aidriven.spi.provider.AiProvider;
import com.aidriven.spi.provider.IssueTrackerProvider;
import com.aidriven.spi.provider.ProviderRegistry;
import com.aidriven.spi.provider.SourceControlProvider;
import com.aidriven.tool.context.ContextService;
import com.aidriven.tool.context.FullRepoStrategy;
import com.aidriven.tool.context.SmartContextStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.aidriven.core.audit.AuditService;
import com.aidriven.core.observability.CloudWatchObservabilityClient;
import com.aidriven.core.security.DynamoDbRateLimiter;
import com.aidriven.core.security.RateLimiter;
import com.aidriven.spi.observability.ObservabilityClient;
import com.aidriven.tool.observability.ObservabilityToolProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.swarm.*;
import com.aidriven.lambda.agent.ToolRegistryBuilder;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import com.aidriven.core.observability.CloudWatchObservabilityClient;
import com.aidriven.tool.observability.ObservabilityToolProvider;
import com.aidriven.tool.source.SourceControlToolProvider;
import com.aidriven.tool.tracker.IssueTrackerToolProvider;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Singleton factory for creating services and clients.
 * Ensures single instances of expensive clients (DynamoDB, SecretsManager) are
 * reused across Lambda invocations using a thread-safe ConcurrentHashMap cache.
 */
@Slf4j
public class ServiceFactory {

    private static final ServiceFactory INSTANCE = new ServiceFactory();

    // Thread-safe singleton registry
    private final ConcurrentHashMap<String, Object> singletonCache = new ConcurrentHashMap<>();

    // Config
    private final AppConfig appConfig;

    private final AwsClientFactory awsClientFactory = new AwsClientFactory();
    private final AgentSubsystemFactory agentSubsystemFactory;
    private ExternalClientFactory externalClientFactory;

    private ServiceFactory() {
        this.appConfig = AppConfig.getInstance();
        this.agentSubsystemFactory = new AgentSubsystemFactory(awsClientFactory, appConfig);
        // ExternalClientFactory initialized lazily to break circular dependency with
        // SecretsService
    }

    private ExternalClientFactory getExternalClientFactory() {
        if (externalClientFactory == null) {
            externalClientFactory = new ExternalClientFactory(getSecretsProvider(), appConfig);
        }
        return externalClientFactory;
    }

    public static ServiceFactory getInstance() {
        return INSTANCE;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    /**
     * Lazy-loads or creates a singleton instance.
     */
    @SuppressWarnings("unchecked")
    private <T> T getCached(String key, Supplier<T> supplier) {
        return (T) singletonCache.computeIfAbsent(key, k -> supplier.get());
    }

    // --- AWS & Core Dependencies ---

    public ObjectMapper getObjectMapper() {
        return getCached("ObjectMapper", () -> {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            return mapper;
        });
    }

    public DynamoDbClient getDynamoDbClient() {
        return awsClientFactory.dynamoDb();
    }

    public SecretsManagerClient getSecretsManagerClient() {
        return awsClientFactory.secretsManager();
    }

    public S3Client getS3Client() {
        return awsClientFactory.s3();
    }

    public SqsClient getSqsClient() {
        return awsClientFactory.sqs();
    }

    public SfnClient getSfnClient() {
        return awsClientFactory.sfn();
    }

    public SecretsService getSecretsProvider() {
        // Pre-compute to avoid recursive computeIfAbsent
        SecretsManagerClient secretsManager = getSecretsManagerClient();
        ObjectMapper mapper = getObjectMapper();
        return getCached("SecretsService", () -> new SecretsServiceImpl(secretsManager, mapper));
    }

    public RateLimiter getRateLimiter() {
        DynamoDbClient dynamo = getDynamoDbClient();
        return getCached("RateLimiter",
                () -> new DynamoDbRateLimiter(dynamo, appConfig.getDynamoDbTableName()));
    }

    public AuditService getAuditService() {
        S3Client s3 = getS3Client();
        ObjectMapper mapper = getObjectMapper();
        return getCached("AuditService",
                () -> new AuditService(s3, mapper, appConfig));
    }

    public ObservabilityClient getObservabilityClient() {
        return getCached("ObservabilityClient", () -> {
            CloudWatchLogsClient logsClient = CloudWatchLogsClient.create();
            return new CloudWatchObservabilityClient(logsClient);
        });
    }

    public ObservabilityToolProvider getObservabilityToolProvider() {
        return getCached("ObservabilityToolProvider",
                () -> new ObservabilityToolProvider(getObservabilityClient()));
    }

    // --- Persistence Repositories ---

    public TicketStateRepository getTicketStateRepository() {
        DynamoDbClient dynamo = getDynamoDbClient();
        return getCached("TicketStateRepository",
                () -> new TicketStateRepository(dynamo, appConfig.getDynamoDbTableName()));
    }

    public ConversationRepository getConversationRepository() {
        return agentSubsystemFactory.conversationRepository();
    }

    public ConversationWindowManager getConversationWindowManager() {
        return agentSubsystemFactory.conversationWindowManager();
    }

    public GenerationMetricsRepository getGenerationMetricsRepository() {
        DynamoDbClient dynamo = getDynamoDbClient();
        return getCached("GenerationMetricsRepository",
                () -> new GenerationMetricsRepository(dynamo, appConfig.getDynamoDbTableName()));
    }

    // --- Services ---

    public ContextStorageService getContextStorageService() {
        S3Client s3 = getS3Client();
        return getCached("ContextStorageService",
                () -> new CodeContextS3ServiceImpl(s3, appConfig.getCodeContextBucket()));
    }

    public IdempotencyService getIdempotencyService() {
        // Pre-compute to avoid recursive computeIfAbsent
        TicketStateRepository repo = getTicketStateRepository();
        return getCached("IdempotencyService", () -> new IdempotencyService(repo));
    }

    public BudgetTracker getBudgetTracker() {
        return getCached("BudgetTracker", () -> {
            CloudWatchClient cw = CloudWatchClient.create();
            return new BudgetTracker(cw, appConfig.getMonthlyBudgetUsd());
        });
    }

    // --- External Clients ---

    public JiraClient getJiraClient() {
        return getExternalClientFactory().jiraClient();
    }

    public CircuitBreaker getJiraCircuitBreaker() {
        return getExternalClientFactory().jiraCircuitBreaker();
    }

    public JiraCommentFormatter getJiraCommentFormatter() {
        return getCached("JiraCommentFormatter", JiraCommentFormatter::new);
    }

    public WorkflowContextProvider getWorkflowContextProvider() {
        TicketStateRepository repo = getTicketStateRepository();
        return getCached("WorkflowContextProvider",
                () -> new WorkflowContextProvider(repo));
    }

    public BitbucketClient getBitbucketClient() {
        return getExternalClientFactory().bitbucketClient();
    }

    public BitbucketClient getBitbucketClient(String workspace, String repoSlug) {
        return getExternalClientFactory().bitbucketClient(workspace, repoSlug);
    }

    public GitHubClient getGitHubClient() {
        return getExternalClientFactory().gitHubClient();
    }

    public CircuitBreaker getGitHubCircuitBreaker() {
        return getExternalClientFactory().gitHubCircuitBreaker();
    }

    public GitHubClient getGitHubClient(String owner, String repo) {
        return getExternalClientFactory().gitHubClient(owner, repo);
    }

    public SourceControlClient getSourceControlClient(Platform platform) {
        return getExternalClientFactory().sourceControlClient(platform);
    }

    public AiClient getClaudeClient() {
        return getExternalClientFactory().claudeClient();
    }

    // --- Context Routing ---

    public ContextService createContextService() {
        return createContextService(getBitbucketClient());
    }

    public ContextService createContextService(SourceControlClient sourceControlClient) {
        ContextStrategy smartStrategy = new SmartContextStrategy(
                sourceControlClient,
                appConfig.getMaxFileSizeChars(),
                appConfig.getMaxTotalContextChars());

        ContextStrategy fullRepoStrategy = new FullRepoStrategy(
                sourceControlClient,
                appConfig.getMaxFileSizeChars(),
                (long) appConfig.getMaxTotalContextChars());

        return new ContextService(smartStrategy, fullRepoStrategy, appConfig.getContextMode());
    }

    // --- Guardrails & Agent Sub-systems ---

    public ApprovalStore getApprovalStore() {
        return agentSubsystemFactory.approvalStore();
    }

    public CostTracker getCostTracker() {
        return agentSubsystemFactory.costTracker();
    }

    public GuardedToolRegistry createGuardedToolRegistry(ToolRegistry toolRegistry) {
        return agentSubsystemFactory.createGuardedToolRegistry(toolRegistry);
    }

    // --- MCP Integration ---

    public McpProviderFactory getMcpProviderFactory() {
        ObjectMapper mapper = getObjectMapper();
        SecretsService secrets = getSecretsProvider();
        return getCached("McpProviderFactory", () -> new McpProviderFactory(mapper, secrets));
    }

    public McpConnectionFactory getMcpConnectionFactory() {
        return getMcpProviderFactory().getConnectionFactory();
    }

    /**
     * Gets MCP tool providers.
     * If MCP Gateway is enabled, returns gateway clients; otherwise falls back to
     * legacy stdio/http providers.
     */
    public List<McpBridgeToolProvider> getMcpToolProviders() {
        // Legacy stdio/http MCP providers (deprecated)
        if (!appConfig.isMcpGatewayEnabled()) {
            McpProviderFactory factory = getMcpProviderFactory();
            return getCached("McpToolProviders",
                    () -> factory.createProviders(appConfig.getMcpServersConfig()));
        }
        // MCP Gateway disabled, return empty list (gateway clients registered
        // separately)
        return List.of();
    }

    /**
     * Gets MCP Gateway clients for all available namespaces.
     * Only available when MCP_GATEWAY_ENABLED=true and MCP_GATEWAY_URL is set.
     *
     * @return List of gateway clients, or empty list if gateway is disabled
     */
    public List<McpGatewayClient> getMcpGatewayClients() {
        if (!appConfig.isMcpGatewayEnabled()) {
            return List.of();
        }

        return appConfig.getMcpGatewayUrl()
                .map(url -> getCached("McpGatewayClients",
                        () -> McpGatewayClient.createAllClients(url, getObjectMapper())))
                .orElse(List.of());
    }

    public ProviderRegistry getProviderRegistry() {
        // Pre-compute dependencies to avoid recursive computeIfAbsent
        // ConcurrentHashMap.computeIfAbsent throws IllegalStateException on recursive
        // updates
        GitHubClient github = getGitHubClient();
        BitbucketClient bitbucket = getBitbucketClient();
        JiraClient jira = getJiraClient();
        AiProvider claude = (AiProvider) getClaudeClient();

        return getCached("ProviderRegistry", () -> {
            ProviderRegistry registry = new ProviderRegistry();
            registry.register(SourceControlProvider.class, "github", github);
            registry.register(SourceControlProvider.class, "bitbucket", bitbucket);
            registry.register(IssueTrackerProvider.class, "jira", jira);
            registry.register(AiProvider.class, "claude", claude);
            return registry;
        });
    }

    public ManagedMcpToolProvider getManagedMcpToolProvider() {
        // Pre-compute to avoid recursive computeIfAbsent
        ProviderRegistry registry = getProviderRegistry();
        return getCached("ManagedMcpToolProvider", () -> new ManagedMcpToolProvider(registry));
    }

    // --- Swarm Orchestration ---

    public ToolRegistryBuilder getToolRegistryBuilder() {
        JiraClient jira = getJiraClient();
        return getCached("ToolRegistryBuilder", () -> new ToolRegistryBuilder(this, jira));
    }

    public SwarmOrchestrator getSwarmOrchestrator(SourceControlClient scClient, ProgressTracker tracker) {
        // Routing client (typically Haiku for speed/cost)
        AiClient routingClient = getExternalClientFactory().researcherClient();

        WorkerAgent coder = getCoderAgent(scClient, tracker);
        WorkerAgent researcher = getResearcherAgent(scClient, tracker);

        return new SwarmOrchestrator(routingClient, coder, researcher);
    }

    public CoderAgent getCoderAgent(SourceControlClient scClient, ProgressTracker tracker) {
        AgentOrchestrator orchestrator = AgentOrchestrator.builder()
                .aiClient(getClaudeClient())
                .windowManager(getConversationWindowManager())
                .costTracker(getCostTracker())
                .agentConfig(appConfig.getAgentConfig())
                .guardedToolRegistry(getToolRegistryBuilder().buildGuarded(scClient))
                .progressTracker(tracker)
                .workflowContextProvider(getWorkflowContextProvider())
                .build();
        return new CoderAgent(orchestrator);
    }

    public ResearcherAgent getResearcherAgent(SourceControlClient scClient, ProgressTracker tracker) {
        AgentOrchestrator orchestrator = AgentOrchestrator.builder()
                .aiClient(getExternalClientFactory().researcherClient())
                .windowManager(getConversationWindowManager())
                .costTracker(getCostTracker())
                .agentConfig(appConfig.getAgentConfig())
                // Use read-only tools for research
                .guardedToolRegistry(getToolRegistryBuilder().buildGuardedReadOnly(scClient))
                .progressTracker(tracker)
                .workflowContextProvider(getWorkflowContextProvider())
                .build();
        return new ResearcherAgent(orchestrator);
    }

}
