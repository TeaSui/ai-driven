package com.aidriven.lambda.factory;

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
import com.aidriven.core.notification.SlackNotifier;
import com.aidriven.spi.notification.ApprovalNotifier;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lazy-initialised factory for agent-mode subsystems.
 *
 * <p>
 * Extracted from {@link ServiceFactory} to satisfy SRP: the agent's
 * conversation, cost-tracking, and guardrail concerns are logically separate
 * from AWS client lifecycle management and external API client wiring.
 */
public class AgentSubsystemFactory {

    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
    private final AwsClientFactory awsFactory;
    private final AppConfig appConfig;

    public AgentSubsystemFactory(AwsClientFactory awsFactory, AppConfig appConfig) {
        this.awsFactory = awsFactory;
        this.appConfig = appConfig;
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> supplier) {
        return (T) cache.computeIfAbsent(key, k -> supplier.get());
    }

    public ConversationRepository conversationRepository() {
        return cached("ConversationRepository",
                () -> new DynamoConversationRepository(
                        awsFactory.dynamoDb(), appConfig.getDynamoDbTableName()));
    }

    public ConversationWindowManager conversationWindowManager() {
        return cached("ConversationWindowManager", () -> {
            AgentConfig cfg = appConfig.getAgentConfig();
            return new ConversationWindowManager(
                    conversationRepository(),
                    cfg.tokenBudget(),
                    cfg.recentMessagesToKeep());
        });
    }

    public ApprovalStore approvalStore() {
        return cached("ApprovalStore",
                () -> new ApprovalStore(awsFactory.dynamoDb(), appConfig.getDynamoDbTableName()));
    }

    public CostTracker costTracker() {
        return cached("CostTracker", () -> {
            AgentConfig cfg = appConfig.getAgentConfig();
            return new CostTracker(
                    awsFactory.dynamoDb(), appConfig.getDynamoDbTableName(),
                    cfg.costBudgetPerTicket());
        });
    }

    public ApprovalNotifier approvalNotifier() {
        return cached("ApprovalNotifier", () -> {
            AppConfig.SlackConfig cfg = appConfig.getSlackConfig();
            return new SlackNotifier(
                    java.net.http.HttpClient.newHttpClient(),
                    cfg.webhookUrl().orElse(null),
                    cfg.channel());
        });
    }

    /**
     * Creates a fresh {@link GuardedToolRegistry} wrapping the given base registry.
     * Not cached — a new registry is created per agent invocation so tool
     * registrations don't bleed across requests.
     */
    public GuardedToolRegistry createGuardedToolRegistry(ToolRegistry toolRegistry) {
        AgentConfig cfg = appConfig.getAgentConfig();
        return new GuardedToolRegistry(
                toolRegistry,
                new ToolRiskRegistry(),
                approvalStore(),
                approvalNotifier(),
                appConfig.getSlackConfig().fallbackToJira(),
                cfg.guardrailsEnabled());
    }
}
