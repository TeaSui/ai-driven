package com.aidriven.registry;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.claude.ClaudeClient;
import com.aidriven.core.agent.AiClient;
import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.ConversationWindowManager;
import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.agent.DynamoConversationRepository;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.guardrail.ToolRiskRegistry;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.config.AgentConfig;
import com.aidriven.core.config.AppConfig;
import com.aidriven.core.config.ClaudeConfig;
import com.aidriven.core.config.McpServerConfig;
import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.service.impl.CodeContextS3ServiceImpl;
import com.aidriven.core.service.impl.SecretsServiceImpl;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.github.GitHubClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.mcp.McpConnectionFactory;
import com.aidriven.tool.context.ContextService;
import com.aidriven.tool.context.FullRepoStrategy;
import com.aidriven.tool.context.SmartContextStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.util.ArrayList;
import java.util.List;

/**
 * AWS-native implementation of {@link ServiceRegistry}.
 * <p>
 * Uses DynamoDB, S3, Secrets Manager, and other AWS services.
 * This is the production implementation used by both Lambda handlers
 * and any future AWS-hosted microservice deployment.
 * </p>
 * <p>
 * Thread-safe: all fields are lazily initialized with synchronized blocks.
 * Designed for Lambda execution context reuse (singleton per JVM).
 * </p>
 */
@Slf4j
public class AwsServiceRegistry implements ServiceRegistry {

    private final AppConfig appConfig;
    private final TenantContext tenantContext;

    // Lazy-initialized AWS clients
    private volatile ObjectMapper objectMapper;
    private volatile DynamoDbClient dynamoDbClient;
    private volatile SecretsManagerClient secretsManagerClient;
    private volatile S3Client s3Client;

    // Lazy-initialized services
    private volatile SecretsService secretsService;
    private volatile TicketStateRepository ticketStateRepository;
    private volatile GenerationMetricsRepository generationMetricsRepository;
    private volatile ContextStorageService contextStorageService;
    private volatile IdempotencyService idempotencyService;
    private volatile ConversationRepository conversationRepository;
    private volatile ConversationWindowManager conversationWindowManager;
    private volatile CostTracker costTracker;
    private volatile ApprovalStore approvalStore;

    // Lazy-initialized domain clients
    private volatile JiraClient jiraClient;
    private volatile BitbucketClient bitbucketClient;
    private volatile GitHubClient gitHubClient;
    private volatile ClaudeClient claudeClient;
    private volatile McpConnectionFactory mcpConnectionFactory;
    private volatile List<McpBridgeToolProvider> mcpProviders;

    /**
     * Creates an AWS service registry with default tenant context.
     */
    public AwsServiceRegistry(AppConfig appConfig) {
        this(appConfig, TenantContext.defaultContext());
    }

    /**
     * Creates an AWS service registry for a specific tenant.
     */
    public AwsServiceRegistry(AppConfig appConfig, TenantContext tenantContext) {
        this.appConfig = appConfig;
        this.tenantContext = tenantContext;
    }

    @Override
    public TenantContext getTenantContext() {
        return tenantContext;
    }

    // --- Shared Infrastructure ---

    public synchronized ObjectMapper getObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        return objectMapper;
    }

    public synchronized DynamoDbClient getDynamoDbClient() {
        if (dynamoDbClient == null) {
            dynamoDbClient = DynamoDbClient.create();
        }
        return dynamoDbClient;
    }

    public synchronized SecretsManagerClient getSecretsManagerClient() {
        if (secretsManagerClient == null) {
            secretsManagerClient = SecretsManagerClient.create();
        }
        return secretsManagerClient;
    }

    public synchronized S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.create();
        }
        return s3Client;
    }

    // --- Core Services ---

    @Override
    public synchronized SecretsService getSecretsService() {
        if (secretsService == null) {
            secretsService = new SecretsServiceImpl(getSecretsManagerClient(), getObjectMapper());
        }
        return secretsService;
    }

    @Override
    public synchronized TicketStateRepository getTicketStateRepository() {
        if (ticketStateRepository == null) {
            ticketStateRepository = new TicketStateRepository(
                    getDynamoDbClient(), appConfig.getDynamoDbTableName());
        }
        return ticketStateRepository;
    }

    @Override
    public synchronized GenerationMetricsRepository getGenerationMetricsRepository() {
        if (generationMetricsRepository == null) {
            generationMetricsRepository = new GenerationMetricsRepository(
                    getDynamoDbClient(), appConfig.getDynamoDbTableName());
        }
        return generationMetricsRepository;
    }

    @Override
    public synchronized ContextStorageService getContextStorageService() {
        if (contextStorageService == null) {
            contextStorageService = new CodeContextS3ServiceImpl(
                    getS3Client(), appConfig.getCodeContextBucket());
        }
        return contextStorageService;
    }

    @Override
    public synchronized IdempotencyService getIdempotencyService() {
        if (idempotencyService == null) {
            idempotencyService = new IdempotencyService(getTicketStateRepository());
        }
        return idempotencyService;
    }

    // --- Domain Clients ---

    @Override
    public synchronized IssueTrackerClient getIssueTrackerClient() {
        if (jiraClient == null) {
            jiraClient = JiraClient.fromSecrets(getSecretsService(), appConfig.getJiraSecretArn());
        }
        return jiraClient;
    }

    @Override
    public SourceControlClient getSourceControlClient(Platform platform) {
        return switch (platform) {
            case GITHUB -> getGitHubClientInternal();
            case BITBUCKET -> getBitbucketClientInternal();
        };
    }

    @Override
    public SourceControlClient getSourceControlClient(Platform platform, String owner, String repo) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            return getSourceControlClient(platform);
        }
        return switch (platform) {
            case GITHUB -> getGitHubClientInternal().withRepository(owner, repo);
            case BITBUCKET -> getBitbucketClientInternal().withRepository(owner, repo);
        };
    }

    @Override
    public synchronized AiClient getAiClient() {
        if (claudeClient == null) {
            claudeClient = ClaudeClient.fromSecrets(getSecretsService(), appConfig.getClaudeSecretArn());
            ClaudeConfig config = appConfig.getClaudeConfig();
            claudeClient = claudeClient.withModel(config.model())
                    .withMaxTokens(config.maxTokens())
                    .withTemperature(config.temperature());
        }
        return claudeClient;
    }

    // --- Agent Infrastructure ---

    @Override
    public synchronized ConversationRepository getConversationRepository() {
        if (conversationRepository == null) {
            conversationRepository = new DynamoConversationRepository(
                    getDynamoDbClient(), appConfig.getDynamoDbTableName());
        }
        return conversationRepository;
    }

    @Override
    public synchronized ConversationWindowManager getConversationWindowManager() {
        if (conversationWindowManager == null) {
            AgentConfig config = appConfig.getAgentConfig();
            conversationWindowManager = new ConversationWindowManager(
                    getConversationRepository(),
                    config.tokenBudget(),
                    config.recentMessagesToKeep());
        }
        return conversationWindowManager;
    }

    @Override
    public synchronized CostTracker getCostTracker() {
        if (costTracker == null) {
            AgentConfig config = appConfig.getAgentConfig();
            costTracker = new CostTracker(
                    getDynamoDbClient(), appConfig.getDynamoDbTableName(),
                    config.costBudgetPerTicket());
        }
        return costTracker;
    }

    @Override
    public synchronized ApprovalStore getApprovalStore() {
        if (approvalStore == null) {
            approvalStore = new ApprovalStore(
                    getDynamoDbClient(), appConfig.getDynamoDbTableName());
        }
        return approvalStore;
    }

    @Override
    public GuardedToolRegistry createGuardedToolRegistry(ToolRegistry toolRegistry) {
        AgentConfig config = appConfig.getAgentConfig();
        return new GuardedToolRegistry(
                toolRegistry,
                new ToolRiskRegistry(),
                getApprovalStore(),
                config.guardrailsEnabled());
    }

    // --- Context & Tools ---

    @Override
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

    @Override
    public synchronized List<McpBridgeToolProvider> getMcpToolProviders() {
        if (mcpProviders != null) {
            return mcpProviders;
        }

        mcpProviders = new ArrayList<>();
        String configJson = appConfig.getMcpServersConfig();

        if (configJson == null || configJson.isBlank() || "[]".equals(configJson.trim())) {
            return mcpProviders;
        }

        try {
            McpServerConfig[] configs = getObjectMapper().readValue(configJson, McpServerConfig[].class);
            McpConnectionFactory factory = getMcpConnectionFactoryInternal();

            for (McpServerConfig config : configs) {
                if (!config.enabled()) {
                    log.info("MCP server '{}' is disabled, skipping", config.namespace());
                    continue;
                }
                try {
                    McpBridgeToolProvider provider = factory.createProvider(config);
                    mcpProviders.add(provider);
                    log.info("Registered MCP tool provider: {}", provider);
                } catch (Exception e) {
                    log.error("Failed to connect MCP server '{}': {}", config.namespace(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse MCP_SERVERS_CONFIG: {}", e.getMessage(), e);
        }

        return mcpProviders;
    }

    // --- Internal helpers ---

    private synchronized BitbucketClient getBitbucketClientInternal() {
        if (bitbucketClient == null) {
            bitbucketClient = BitbucketClient.fromSecrets(
                    getSecretsService(), appConfig.getBitbucketSecretArn());
        }
        return bitbucketClient;
    }

    private synchronized GitHubClient getGitHubClientInternal() {
        if (gitHubClient == null) {
            String secretArn = appConfig.getGitHubSecretArn();
            if (secretArn == null || secretArn.isBlank()) {
                throw new IllegalStateException("GITHUB_SECRET_ARN is not configured");
            }
            gitHubClient = GitHubClient.fromSecrets(getSecretsService(), secretArn);
        }
        return gitHubClient;
    }

    private synchronized McpConnectionFactory getMcpConnectionFactoryInternal() {
        if (mcpConnectionFactory == null) {
            mcpConnectionFactory = new McpConnectionFactory(getSecretsService());
        }
        return mcpConnectionFactory;
    }

    /** Exposes AppConfig for handlers that need direct access. */
    public AppConfig getAppConfig() {
        return appConfig;
    }
}
