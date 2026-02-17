package com.aidriven.lambda.factory;

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
import com.aidriven.registry.AwsServiceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Lambda-specific singleton factory for creating services and clients.
 * <p>
 * Delegates to {@link AwsServiceRegistry} for all service creation,
 * adding Lambda-specific clients (SFN, SQS) that are not part of the
 * platform-agnostic registry.
 * </p>
 * <p>
 * This class exists for backward compatibility with existing Lambda handlers.
 * New code should prefer injecting {@link com.aidriven.registry.ServiceRegistry} directly.
 * </p>
 */
@Slf4j
public class ServiceFactory {

    private static final ServiceFactory INSTANCE = new ServiceFactory();

    private final AppConfig appConfig;
    private final AwsServiceRegistry registry;

    // Lambda-specific clients (not in ServiceRegistry)
    private volatile SfnClient sfnClient;
    private volatile SqsClient sqsClient;

    private ServiceFactory() {
        this.appConfig = AppConfig.getInstance();
        this.registry = new AwsServiceRegistry(appConfig);
    }

    public static ServiceFactory getInstance() {
        return INSTANCE;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    /** Returns the underlying service registry for direct access. */
    public AwsServiceRegistry getRegistry() {
        return registry;
    }

    // --- Delegate to AwsServiceRegistry ---

    public ObjectMapper getObjectMapper() {
        return registry.getObjectMapper();
    }

    public DynamoDbClient getDynamoDbClient() {
        return registry.getDynamoDbClient();
    }

    public SecretsService getSecretsProvider() {
        return registry.getSecretsService();
    }

    public TicketStateRepository getTicketStateRepository() {
        return registry.getTicketStateRepository();
    }

    public ConversationRepository getConversationRepository() {
        return registry.getConversationRepository();
    }

    public ConversationWindowManager getConversationWindowManager() {
        return registry.getConversationWindowManager();
    }

    public GenerationMetricsRepository getGenerationMetricsRepository() {
        return registry.getGenerationMetricsRepository();
    }

    public ContextStorageService getContextStorageService() {
        return registry.getContextStorageService();
    }

    public IdempotencyService getIdempotencyService() {
        return registry.getIdempotencyService();
    }

    public JiraClient getJiraClient() {
        return (JiraClient) registry.getIssueTrackerClient();
    }

    public BitbucketClient getBitbucketClient() {
        return (BitbucketClient) registry.getSourceControlClient(Platform.BITBUCKET);
    }

    public BitbucketClient getBitbucketClient(String workspace, String repoSlug) {
        if (workspace == null || workspace.isBlank() || repoSlug == null || repoSlug.isBlank()) {
            return getBitbucketClient();
        }
        return (BitbucketClient) registry.getSourceControlClient(Platform.BITBUCKET, workspace, repoSlug);
    }

    public GitHubClient getGitHubClient() {
        return (GitHubClient) registry.getSourceControlClient(Platform.GITHUB);
    }

    public GitHubClient getGitHubClient(String owner, String repo) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            return getGitHubClient();
        }
        return (GitHubClient) registry.getSourceControlClient(Platform.GITHUB, owner, repo);
    }

    public SourceControlClient getSourceControlClient(Platform platform) {
        return registry.getSourceControlClient(platform);
    }

    public ClaudeClient getClaudeClient() {
        return (ClaudeClient) registry.getAiClient();
    }

    public ContextService createContextService() {
        return registry.createContextService(getBitbucketClient());
    }

    public ContextService createContextService(SourceControlClient sourceControlClient) {
        return registry.createContextService(sourceControlClient);
    }

    public ApprovalStore getApprovalStore() {
        return registry.getApprovalStore();
    }

    public CostTracker getCostTracker() {
        return registry.getCostTracker();
    }

    public GuardedToolRegistry createGuardedToolRegistry(ToolRegistry toolRegistry) {
        return registry.createGuardedToolRegistry(toolRegistry);
    }

    public List<McpBridgeToolProvider> getMcpToolProviders() {
        return registry.getMcpToolProviders();
    }

    // --- Lambda-specific clients ---

    public synchronized SfnClient getSfnClient() {
        if (sfnClient == null) {
            sfnClient = SfnClient.create();
        }
        return sfnClient;
    }

    public synchronized SqsClient getSqsClient() {
        if (sqsClient == null) {
            sqsClient = SqsClient.create();
        }
        return sqsClient;
    }
}
