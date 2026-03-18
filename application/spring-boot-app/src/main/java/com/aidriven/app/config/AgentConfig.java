package com.aidriven.app.config;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.core.agent.AiClient;
import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.ConversationWindowManager;
import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.WorkflowContextProvider;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.guardrail.ToolRiskRegistry;
import com.aidriven.core.agent.tool.ManagedMcpToolProvider;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.swarm.CoderAgent;
import com.aidriven.core.agent.swarm.ResearcherAgent;
import com.aidriven.core.agent.swarm.ReviewerAgent;
import com.aidriven.core.agent.swarm.SwarmOrchestrator;
import com.aidriven.core.agent.swarm.TesterAgent;
import com.aidriven.core.notification.SlackNotifier;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.github.GitHubClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.mcp.McpGatewayClient;
import com.aidriven.spi.notification.ApprovalNotifier;
import com.aidriven.spi.provider.AiProvider;
import com.aidriven.spi.provider.IssueTrackerProvider;
import com.aidriven.spi.provider.ProviderRegistry;
import com.aidriven.spi.provider.SourceControlProvider;
import com.aidriven.tool.context.CodeContextToolProvider;
import com.aidriven.tool.context.ContextService;
import com.aidriven.tool.context.FullRepoStrategy;
import com.aidriven.tool.context.SmartContextStrategy;
import com.aidriven.core.agent.tool.ReadOnlyToolProvider;
import com.aidriven.core.context.ContextMode;
import com.aidriven.core.context.ContextStrategy;
import com.aidriven.core.security.JiraWebhookSecretResolver;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.source.SourceControlClientResolver;
import com.aidriven.tool.source.SourceControlToolProvider;
import com.aidriven.tool.tracker.IssueTrackerToolProvider;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;

/**
 * Agent subsystem beans: conversation management, cost tracking, guardrails,
 * tool registry builder, and swarm orchestration factory.
 *
 * <p>Replaces {@code AgentSubsystemFactory} and the agent/swarm wiring in
 * {@code ServiceFactory}. Singleton beans cover stateless/shared components;
 * request-scoped components (SwarmOrchestrator, worker agents) are created
 * via the {@link SwarmOrchestratorFactory} bean.
 */
@Configuration
public class AgentConfig {

    // ---- Conversation ----

    @Bean
    ConversationWindowManager conversationWindowManager(ConversationRepository conversationRepository,
                                                         AppProperties properties) {
        AppProperties.AgentProperties agent = properties.agent();
        return new ConversationWindowManager(
                conversationRepository,
                agent.tokenBudget(),
                agent.recentMessagesToKeep());
    }

    // ---- Cost Tracking ----

    @Bean
    CostTracker costTracker(DynamoDbClient dynamoDbClient, AppProperties properties) {
        return new CostTracker(
                dynamoDbClient,
                properties.aws().dynamodb().stateTable(),
                properties.agent().costBudgetPerTicket());
    }

    // ---- Approval / Guardrails ----

    @Bean
    ApprovalStore approvalStore(DynamoDbClient dynamoDbClient, AppProperties properties) {
        return new ApprovalStore(dynamoDbClient, properties.aws().dynamodb().stateTable());
    }

    @Bean
    ApprovalNotifier approvalNotifier(AppProperties properties) {
        AppProperties.SlackProperties slack = properties.slack();
        String webhookUrl = slack != null ? slack.webhookUrl() : null;
        Optional<String> channel = slack != null
                ? Optional.ofNullable(slack.channel()).filter(s -> !s.isBlank())
                : Optional.empty();
        return new SlackNotifier(HttpClient.newHttpClient(), webhookUrl, channel);
    }

    @Bean
    ToolRiskRegistry toolRiskRegistry() {
        return new ToolRiskRegistry();
    }

    // ---- Workflow Context ----

    @Bean
    WorkflowContextProvider workflowContextProvider(TicketStateRepository ticketStateRepository) {
        return new WorkflowContextProvider(ticketStateRepository);
    }

    @Bean
    JiraCommentFormatter jiraCommentFormatter() {
        return new JiraCommentFormatter();
    }

    // ---- Webhook Security ----

    @Bean
    JiraWebhookSecretResolver jiraWebhookSecretResolver(AppProperties properties, SecretsService secretsService) {
        return new JiraWebhookSecretResolver(
                properties.jira().webhookSecret(),
                properties.jira().webhookSecretArn(),
                secretsService);
    }

    // ---- Source Control Resolution ----

    @Bean
    SourceControlClientResolver sourceControlClientResolver(
            GitHubClient gitHubClient,
            BitbucketClient bitbucketClient,
            AppProperties properties) {
        AppProperties.ContextProperties ctx = properties.context();
        String defaultPlatform = ctx.defaultPlatform();
        String defaultWorkspace = ctx.defaultWorkspace();
        String defaultRepo = ctx.defaultRepo();

        return new SourceControlClientResolver(
                (platform, owner, slug) -> {
                    if ("GITHUB".equalsIgnoreCase(platform)) {
                        return gitHubClient.withRepository(owner, slug);
                    }
                    return bitbucketClient.withRepository(owner, slug);
                },
                defaultPlatform,
                defaultWorkspace,
                defaultRepo);
    }

    // ---- Provider Registry ----

    @Bean
    ProviderRegistry providerRegistry(
            GitHubClient gitHubClient,
            BitbucketClient bitbucketClient,
            JiraClient jiraClient,
            @Qualifier("mainAiClient") AiClient mainAiClient) {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(SourceControlProvider.class, "github", gitHubClient);
        registry.register(SourceControlProvider.class, "bitbucket", bitbucketClient);
        registry.register(IssueTrackerProvider.class, "jira", jiraClient);
        registry.register(AiProvider.class, "claude", (AiProvider) mainAiClient);
        return registry;
    }

    @Bean
    ManagedMcpToolProvider managedMcpToolProvider(ProviderRegistry providerRegistry) {
        return new ManagedMcpToolProvider(providerRegistry);
    }

    // ---- Swarm Orchestrator Factory ----

    /**
     * Factory bean for creating per-request SwarmOrchestrator instances.
     *
     * <p>SwarmOrchestrator and its worker agents (Coder, Researcher, Reviewer, Tester)
     * require a request-specific {@link SourceControlClient} and {@link ProgressTracker},
     * so they cannot be singletons. This factory captures all shared dependencies and
     * creates fresh orchestrators on demand.
     */
    @Bean
    SwarmOrchestratorFactory swarmOrchestratorFactory(
            @Qualifier("mainAiClient") AiClient mainAiClient,
            @Qualifier("researcherAiClient") AiClient researcherAiClient,
            @Qualifier("reviewerAiClient") AiClient reviewerAiClient,
            @Qualifier("testerAiClient") AiClient testerAiClient,
            ConversationWindowManager windowManager,
            CostTracker costTracker,
            WorkflowContextProvider workflowContextProvider,
            AppProperties properties,
            ApprovalStore approvalStore,
            ApprovalNotifier approvalNotifier,
            ToolRiskRegistry toolRiskRegistry,
            JiraClient jiraClient,
            ManagedMcpToolProvider managedMcpToolProvider,
            List<McpBridgeToolProvider> mcpToolProviders,
            List<McpGatewayClient> mcpGatewayClients) {

        return new SwarmOrchestratorFactory(
                mainAiClient, researcherAiClient, reviewerAiClient, testerAiClient,
                windowManager, costTracker, workflowContextProvider,
                properties, approvalStore, approvalNotifier, toolRiskRegistry,
                jiraClient, managedMcpToolProvider, mcpToolProviders, mcpGatewayClients);
    }

    /**
     * Provides request-scoped SwarmOrchestrator creation.
     *
     * <p>Mirrors the per-request agent creation in
     * {@code ServiceFactory.getSwarmOrchestrator(SourceControlClient, ProgressTracker)}.
     */
    public static class SwarmOrchestratorFactory {

        private final AiClient mainAiClient;
        private final AiClient researcherAiClient;
        private final AiClient reviewerAiClient;
        private final AiClient testerAiClient;
        private final ConversationWindowManager windowManager;
        private final CostTracker costTracker;
        private final WorkflowContextProvider workflowContextProvider;
        private final AppProperties properties;
        private final ApprovalStore approvalStore;
        private final ApprovalNotifier approvalNotifier;
        private final ToolRiskRegistry toolRiskRegistry;
        private final JiraClient jiraClient;
        private final ManagedMcpToolProvider managedMcpToolProvider;
        private final List<McpBridgeToolProvider> mcpToolProviders;
        private final List<McpGatewayClient> mcpGatewayClients;

        public SwarmOrchestratorFactory(
                AiClient mainAiClient,
                AiClient researcherAiClient,
                AiClient reviewerAiClient,
                AiClient testerAiClient,
                ConversationWindowManager windowManager,
                CostTracker costTracker,
                WorkflowContextProvider workflowContextProvider,
                AppProperties properties,
                ApprovalStore approvalStore,
                ApprovalNotifier approvalNotifier,
                ToolRiskRegistry toolRiskRegistry,
                JiraClient jiraClient,
                ManagedMcpToolProvider managedMcpToolProvider,
                List<McpBridgeToolProvider> mcpToolProviders,
                List<McpGatewayClient> mcpGatewayClients) {
            this.mainAiClient = mainAiClient;
            this.researcherAiClient = researcherAiClient;
            this.reviewerAiClient = reviewerAiClient;
            this.testerAiClient = testerAiClient;
            this.windowManager = windowManager;
            this.costTracker = costTracker;
            this.workflowContextProvider = workflowContextProvider;
            this.properties = properties;
            this.approvalStore = approvalStore;
            this.approvalNotifier = approvalNotifier;
            this.toolRiskRegistry = toolRiskRegistry;
            this.jiraClient = jiraClient;
            this.managedMcpToolProvider = managedMcpToolProvider;
            this.mcpToolProviders = mcpToolProviders;
            this.mcpGatewayClients = mcpGatewayClients;
        }

        /**
         * Creates a new SwarmOrchestrator for a specific request.
         *
         * @param scClient the source control client for this request
         * @param tracker  the progress tracker for this request
         * @return a fully wired SwarmOrchestrator
         */
        public SwarmOrchestrator create(SourceControlClient scClient, ProgressTracker tracker) {
            CoderAgent coder = createCoderAgent(scClient, tracker);
            ResearcherAgent researcher = createResearcherAgent(scClient, tracker);
            ReviewerAgent reviewer = createReviewerAgent(scClient, tracker);
            TesterAgent tester = createTesterAgent(scClient, tracker);

            return new SwarmOrchestrator(researcherAiClient, coder, researcher, reviewer, tester);
        }

        /**
         * Creates a standalone AgentOrchestrator for non-swarm use.
         */
        public AgentOrchestrator createAgentOrchestrator(SourceControlClient scClient,
                                                          ProgressTracker tracker) {
            return AgentOrchestrator.builder()
                    .aiClient(mainAiClient)
                    .windowManager(windowManager)
                    .costTracker(costTracker)
                    .maxTurns(properties.agent().maxTurns())
                    .guardedToolRegistry(buildGuardedRegistry(scClient))
                    .progressTracker(tracker)
                    .workflowContextProvider(workflowContextProvider)
                    .build();
        }

        private CoderAgent createCoderAgent(SourceControlClient scClient, ProgressTracker tracker) {
            AgentOrchestrator orchestrator = AgentOrchestrator.builder()
                    .aiClient(mainAiClient)
                    .windowManager(windowManager)
                    .costTracker(costTracker)
                    .maxTurns(properties.agent().maxTurns())
                    .guardedToolRegistry(buildGuardedRegistry(scClient))
                    .progressTracker(tracker)
                    .workflowContextProvider(workflowContextProvider)
                    .build();
            return new CoderAgent(orchestrator);
        }

        private ResearcherAgent createResearcherAgent(SourceControlClient scClient,
                                                       ProgressTracker tracker) {
            AgentOrchestrator orchestrator = AgentOrchestrator.builder()
                    .aiClient(researcherAiClient)
                    .windowManager(windowManager)
                    .costTracker(costTracker)
                    .maxTurns(properties.agent().maxTurns())
                    .guardedToolRegistry(buildGuardedReadOnlyRegistry(scClient))
                    .progressTracker(tracker)
                    .workflowContextProvider(workflowContextProvider)
                    .build();
            return new ResearcherAgent(orchestrator);
        }

        private ReviewerAgent createReviewerAgent(SourceControlClient scClient,
                                                    ProgressTracker tracker) {
            AgentOrchestrator orchestrator = AgentOrchestrator.builder()
                    .aiClient(reviewerAiClient)
                    .windowManager(windowManager)
                    .costTracker(costTracker)
                    .maxTurns(properties.agent().maxTurns())
                    .guardedToolRegistry(buildGuardedReadOnlyRegistry(scClient))
                    .progressTracker(tracker)
                    .workflowContextProvider(workflowContextProvider)
                    .build();
            return new ReviewerAgent(orchestrator);
        }

        private TesterAgent createTesterAgent(SourceControlClient scClient, ProgressTracker tracker) {
            AgentOrchestrator orchestrator = AgentOrchestrator.builder()
                    .aiClient(testerAiClient)
                    .windowManager(windowManager)
                    .costTracker(costTracker)
                    .maxTurns(properties.agent().maxTurns())
                    .guardedToolRegistry(buildGuardedReadOnlyRegistry(scClient))
                    .progressTracker(tracker)
                    .workflowContextProvider(workflowContextProvider)
                    .build();
            return new TesterAgent(orchestrator);
        }

        // ---- Tool Registry Building ----

        private ToolRegistry buildToolRegistry(SourceControlClient scClient) {
            ToolRegistry toolRegistry = new ToolRegistry();

            toolRegistry.register(new SourceControlToolProvider(scClient));
            toolRegistry.register(new IssueTrackerToolProvider(jiraClient));

            ContextService contextService = createContextService(scClient);
            toolRegistry.register(new CodeContextToolProvider(contextService));

            for (McpBridgeToolProvider mcpProvider : mcpToolProviders) {
                toolRegistry.register(mcpProvider);
            }
            for (McpGatewayClient gatewayClient : mcpGatewayClients) {
                toolRegistry.register(gatewayClient);
            }
            if (managedMcpToolProvider != null) {
                toolRegistry.register(managedMcpToolProvider);
            }

            return toolRegistry;
        }

        private ToolRegistry buildReadOnlyToolRegistry(SourceControlClient scClient) {
            ToolRegistry toolRegistry = new ToolRegistry();

            toolRegistry.register(new ReadOnlyToolProvider(new SourceControlToolProvider(scClient)));
            toolRegistry.register(new ReadOnlyToolProvider(new IssueTrackerToolProvider(jiraClient)));

            ContextService contextService = createContextService(scClient);
            toolRegistry.register(new ReadOnlyToolProvider(new CodeContextToolProvider(contextService)));

            for (McpBridgeToolProvider mcpProvider : mcpToolProviders) {
                toolRegistry.register(new ReadOnlyToolProvider(mcpProvider));
            }
            for (McpGatewayClient gatewayClient : mcpGatewayClients) {
                toolRegistry.register(new ReadOnlyToolProvider(gatewayClient));
            }
            if (managedMcpToolProvider != null) {
                toolRegistry.register(new ReadOnlyToolProvider(managedMcpToolProvider));
            }

            return toolRegistry;
        }

        public GuardedToolRegistry buildGuardedRegistry(SourceControlClient scClient) {
            ToolRegistry baseRegistry = buildToolRegistry(scClient);
            return createGuardedToolRegistry(baseRegistry);
        }

        private GuardedToolRegistry buildGuardedReadOnlyRegistry(SourceControlClient scClient) {
            ToolRegistry baseRegistry = buildReadOnlyToolRegistry(scClient);
            return createGuardedToolRegistry(baseRegistry);
        }

        private GuardedToolRegistry createGuardedToolRegistry(ToolRegistry toolRegistry) {
            boolean fallbackToJira = properties.slack() != null && properties.slack().fallbackToJira();
            return new GuardedToolRegistry(
                    toolRegistry,
                    toolRiskRegistry,
                    approvalStore,
                    approvalNotifier,
                    fallbackToJira,
                    properties.agent().guardrailsEnabled());
        }

        private ContextService createContextService(SourceControlClient scClient) {
            AppProperties.ContextProperties ctx = properties.context();
            ContextStrategy smartStrategy = new SmartContextStrategy(
                    scClient, ctx.maxFileSizeChars(), ctx.maxTotalContextChars());
            ContextStrategy fullRepoStrategy = new FullRepoStrategy(
                    scClient, ctx.maxFileSizeChars(), (long) ctx.maxTotalContextChars());

            String modeName = ctx.mode() != null ? ctx.mode() : "INCREMENTAL";
            ContextMode contextMode;
            try {
                contextMode = ContextMode.valueOf(modeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                contextMode = ContextMode.INCREMENTAL;
            }
            return new ContextService(smartStrategy, fullRepoStrategy, contextMode);
        }
    }
}
