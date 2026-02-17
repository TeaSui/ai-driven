package com.aidriven.agent.factory;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.github.GitHubClient;
import com.aidriven.core.config.AppConfig;
import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.impl.CodeContextS3ServiceImpl;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.service.impl.SecretsServiceImpl;
import com.aidriven.jira.JiraClient;
import com.aidriven.claude.ClaudeClient;
import com.aidriven.core.config.ClaudeConfig;
import com.aidriven.tool.context.ContextService;
import com.aidriven.tool.context.SmartContextStrategy;
import com.aidriven.tool.context.FullRepoStrategy;
import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.agent.DynamoConversationRepository;
import com.aidriven.core.agent.ConversationWindowManager;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.guardrail.ToolRiskRegistry;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.config.AgentConfig;
import com.aidriven.core.config.McpServerConfig;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.mcp.McpConnectionFactory;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.SourceControlClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Service factory for the Agent Service module.
 * Contains all dependencies needed for the agent workflow:
 * AgentWebhook, AgentProcessor, tools, guardrails, MCP.
 *
 * <p>This is a focused version of the original ServiceFactory,
 * excluding pipeline-specific dependencies (SfnClient, Step Functions).</p>
 */
@Slf4j
public class AgentServiceFactory {

    private static final AgentServiceFactory INSTANCE = new AgentServiceFactory();

    private ObjectMapper objectMapper;
    private DynamoDbClient dynamoDbClient;
    private SecretsManagerClient secretsManagerClient;
    private S3Client s3Client;
    private SqsClient sqsClient;

    private SecretsService secretsService;
    private TicketStateRepository ticketStateRepository;
    private ConversationRepository conversationRepository;
    private ConversationWindowManager conversationWindowManager;
    private GenerationMetricsRepository generationMetricsRepository;
    private ContextStorageService codeContextS3Service;
    private IdempotencyService idempotencyService;
    private ClaudeClient claudeClient;
    private JiraClient jiraClient;
    private BitbucketClient bitbucketClient;
    private GitHubClient gitHubClient;

    private ApprovalStore approvalStore;
    private CostTracker costTracker;
    private McpConnectionFactory mcpConnectionFactory;
    private List<McpBridgeToolProvider> mcpProviders;

    private final AppConfig appConfig;

    private AgentServiceFactory() {
        this.appConfig = AppConfig.getInstance();
    }

    public static AgentServiceFactory getInstance() {
        return INSTANCE;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

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

    public synchronized SqsClient getSqsClient() {
        if (sqsClient == null) {
            sqsClient = SqsClient.create();
        }
        return sqsClient;
    }

    public synchronized SecretsService getSecretsProvider() {
        if (secretsService == null) {
            secretsService = new SecretsServiceImpl(getSecretsManagerClient(), getObjectMapper());
        }
        return secretsService;
    }

    public synchronized TicketStateRepository getTicketStateRepository() {
        if (ticketStateRepository == null) {
            ticketStateRepository = new TicketStateRepository(getDynamoDbClient(), appConfig.getDynamoDbTableName());
        }
        return ticketStateRepository;
    }

    public synchronized ConversationRepository getConversationRepository() {
        if (conversationRepository == null) {
            conversationRepository = new DynamoConversationRepository(getDynamoDbClient(),
                    appConfig.getDynamoDbTableName());
        }
        return conversationRepository;
    }

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

    public synchronized GenerationMetricsRepository getGenerationMetricsRepository() {
        if (generationMetricsRepository == null) {
            generationMetricsRepository = new GenerationMetricsRepository(getDynamoDbClient(),
                    appConfig.getDynamoDbTableName());
        }
        return generationMetricsRepository;
    }

    public synchronized ContextStorageService getContextStorageService() {
        if (codeContextS3Service == null) {
            codeContextS3Service = new CodeContextS3ServiceImpl(getS3Client(), appConfig.getCodeContextBucket());
        }
        return codeContextS3Service;
    }

    public synchronized IdempotencyService getIdempotencyService() {
        if (idempotencyService == null) {
            idempotencyService = new IdempotencyService(getTicketStateRepository());
        }
        return idempotencyService;
    }

    public synchronized JiraClient getJiraClient() {
        if (jiraClient == null) {
            jiraClient = JiraClient.fromSecrets(getSecretsProvider(), appConfig.getJiraSecretArn());
        }
        return jiraClient;
    }

    public synchronized BitbucketClient getBitbucketClient() {
        if (bitbucketClient == null) {
            bitbucketClient = BitbucketClient.fromSecrets(getSecretsProvider(), appConfig.getBitbucketSecretArn());
        }
        return bitbucketClient;
    }

    public BitbucketClient getBitbucketClient(String workspace, String repoSlug) {
        if (workspace == null || workspace.isBlank() || repoSlug == null || repoSlug.isBlank()) {
            return getBitbucketClient();
        }
        return getBitbucketClient().withRepository(workspace, repoSlug);
    }

    public synchronized GitHubClient getGitHubClient() {
        if (gitHubClient == null) {
            String secretArn = appConfig.getGitHubSecretArn();
            if (secretArn == null || secretArn.isBlank()) {
                throw new IllegalStateException("GITHUB_SECRET_ARN is not configured");
            }
            gitHubClient = GitHubClient.fromSecrets(getSecretsProvider(), secretArn);
        }
        return gitHubClient;
    }

    public GitHubClient getGitHubClient(String owner, String repo) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            return getGitHubClient();
        }
        return getGitHubClient().withRepository(owner, repo);
    }

    public SourceControlClient getSourceControlClient(Platform platform) {
        return switch (platform) {
            case GITHUB -> getGitHubClient();
            case BITBUCKET -> getBitbucketClient();
        };
    }

    public synchronized ClaudeClient getClaudeClient() {
        if (claudeClient == null) {
            claudeClient = ClaudeClient.fromSecrets(getSecretsProvider(), appConfig.getClaudeSecretArn());
            ClaudeConfig config = appConfig.getClaudeConfig();
            claudeClient = claudeClient.withModel(config.model())
                    .withMaxTokens(config.maxTokens())
                    .withTemperature(config.temperature());
        }
        return claudeClient;
    }

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

    // --- Guardrails + Cost Tracking ---

    public synchronized ApprovalStore getApprovalStore() {
        if (approvalStore == null) {
            approvalStore = new ApprovalStore(getDynamoDbClient(), appConfig.getDynamoDbTableName());
        }
        return approvalStore;
    }

    public synchronized CostTracker getCostTracker() {
        if (costTracker == null) {
            AgentConfig config = appConfig.getAgentConfig();
            costTracker = new CostTracker(getDynamoDbClient(), appConfig.getDynamoDbTableName(),
                    config.costBudgetPerTicket());
        }
        return costTracker;
    }

    public GuardedToolRegistry createGuardedToolRegistry(ToolRegistry toolRegistry) {
        AgentConfig config = appConfig.getAgentConfig();
        return new GuardedToolRegistry(
                toolRegistry,
                new ToolRiskRegistry(),
                getApprovalStore(),
                config.guardrailsEnabled());
    }

    // --- MCP Integration ---

    public synchronized McpConnectionFactory getMcpConnectionFactory() {
        if (mcpConnectionFactory == null) {
            mcpConnectionFactory = new McpConnectionFactory(getSecretsProvider());
        }
        return mcpConnectionFactory;
    }

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
            McpConnectionFactory factory = getMcpConnectionFactory();

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
}
