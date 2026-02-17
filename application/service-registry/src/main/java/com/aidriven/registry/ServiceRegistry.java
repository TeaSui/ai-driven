package com.aidriven.registry;

import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.ConversationWindowManager;
import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.guardrail.ToolRiskRegistry;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.tool.context.ContextService;

import java.util.List;

/**
 * Platform-agnostic service registry interface.
 * <p>
 * Decouples service wiring from any specific runtime (Lambda, Spring Boot, etc.).
 * Each deployment target provides its own implementation.
 * </p>
 *
 * <p>For multi-tenant SaaS, implementations can be tenant-aware by accepting
 * a {@link TenantContext} to resolve tenant-specific credentials and configuration.</p>
 */
public interface ServiceRegistry {

    // --- Configuration ---

    /** Returns the tenant context for the current request scope. */
    TenantContext getTenantContext();

    // --- Core Infrastructure ---

    SecretsService getSecretsService();

    TicketStateRepository getTicketStateRepository();

    GenerationMetricsRepository getGenerationMetricsRepository();

    ContextStorageService getContextStorageService();

    IdempotencyService getIdempotencyService();

    // --- Domain Clients ---

    IssueTrackerClient getIssueTrackerClient();

    SourceControlClient getSourceControlClient(Platform platform);

    SourceControlClient getSourceControlClient(Platform platform, String owner, String repo);

    /** Returns the AI client (e.g., ClaudeClient). */
    com.aidriven.core.agent.AiClient getAiClient();

    // --- Agent Infrastructure ---

    ConversationRepository getConversationRepository();

    ConversationWindowManager getConversationWindowManager();

    CostTracker getCostTracker();

    ApprovalStore getApprovalStore();

    GuardedToolRegistry createGuardedToolRegistry(ToolRegistry toolRegistry);

    // --- Context & Tools ---

    ContextService createContextService(SourceControlClient sourceControlClient);

    /** Returns MCP bridge tool providers configured for the current tenant. */
    List<com.aidriven.mcp.McpBridgeToolProvider> getMcpToolProviders();
}
