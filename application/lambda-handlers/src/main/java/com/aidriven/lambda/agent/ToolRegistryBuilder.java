package com.aidriven.lambda.agent;

import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.tool.ManagedMcpToolProvider;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.mcp.McpGatewayClient;
import com.aidriven.tool.context.CodeContextToolProvider;
import com.aidriven.tool.context.ContextService;
import com.aidriven.tool.source.SourceControlToolProvider;
import com.aidriven.tool.tracker.IssueTrackerToolProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds and configures the tool registry for agent processing.
 * Extracted from AgentProcessorHandler to adhere to Single Responsibility
 * Principle.
 */
@Slf4j
@RequiredArgsConstructor
public class ToolRegistryBuilder {

    private final ServiceFactory serviceFactory;
    private final JiraClient jiraClient;

    /**
     * Builds a complete tool registry with all available tools.
     *
     * @param sourceControlClient The source control client for repository
     *                            operations
     * @return A fully configured ToolRegistry
     */
    public ToolRegistry build(SourceControlClient sourceControlClient) {
        ToolRegistry toolRegistry = new ToolRegistry();

        // Core tools
        toolRegistry.register(new SourceControlToolProvider(sourceControlClient));
        toolRegistry.register(new IssueTrackerToolProvider(jiraClient));

        // Context tools
        ContextService contextService = serviceFactory.createContextService(sourceControlClient);
        toolRegistry.register(new CodeContextToolProvider(contextService));

        // MCP providers (legacy stdio/http)
        for (McpBridgeToolProvider mcpProvider : serviceFactory.getMcpToolProviders()) {
            toolRegistry.register(mcpProvider);
        }

        // MCP Gateway clients (unified gateway)
        for (McpGatewayClient gatewayClient : serviceFactory.getMcpGatewayClients()) {
            toolRegistry.register(gatewayClient);
        }

        // Managed MCP tools
        ManagedMcpToolProvider managedMcp = serviceFactory.getManagedMcpToolProvider();
        if (managedMcp != null) {
            toolRegistry.register(managedMcp);
        }

        log.debug("Built tool registry with {} tools", toolRegistry.getAllToolDefinitions().size());
        return toolRegistry;
    }

    /**
     * Builds a guarded tool registry with guardrails applied.
     *
     * @param sourceControlClient The source control client for repository
     *                            operations
     * @return A GuardedToolRegistry wrapping the base tools
     */
    public GuardedToolRegistry buildGuarded(SourceControlClient sourceControlClient) {
        ToolRegistry baseRegistry = build(sourceControlClient);
        return serviceFactory.createGuardedToolRegistry(baseRegistry);
    }

    /**
     * Builds a tool registry with only read-only tools.
     */
    public ToolRegistry buildReadOnly(SourceControlClient sourceControlClient) {
        ToolRegistry toolRegistry = new ToolRegistry();

        // Wrap all providers in ReadOnlyToolProvider
        toolRegistry.register(new com.aidriven.core.agent.tool.ReadOnlyToolProvider(
                new SourceControlToolProvider(sourceControlClient)));
        toolRegistry.register(new com.aidriven.core.agent.tool.ReadOnlyToolProvider(
                new IssueTrackerToolProvider(jiraClient)));

        ContextService contextService = serviceFactory.createContextService(sourceControlClient);
        toolRegistry.register(new com.aidriven.core.agent.tool.ReadOnlyToolProvider(
                new CodeContextToolProvider(contextService)));

        for (McpBridgeToolProvider mcpProvider : serviceFactory.getMcpToolProviders()) {
            toolRegistry.register(new com.aidriven.core.agent.tool.ReadOnlyToolProvider(mcpProvider));
        }

        for (McpGatewayClient gatewayClient : serviceFactory.getMcpGatewayClients()) {
            toolRegistry.register(new com.aidriven.core.agent.tool.ReadOnlyToolProvider(gatewayClient));
        }

        ManagedMcpToolProvider managedMcp = serviceFactory.getManagedMcpToolProvider();
        if (managedMcp != null) {
            toolRegistry.register(new com.aidriven.core.agent.tool.ReadOnlyToolProvider(managedMcp));
        }

        return toolRegistry;
    }

    /**
     * Builds a guarded read-only tool registry.
     */
    public GuardedToolRegistry buildGuardedReadOnly(SourceControlClient sourceControlClient) {
        ToolRegistry baseRegistry = buildReadOnly(sourceControlClient);
        return serviceFactory.createGuardedToolRegistry(baseRegistry);
    }
}
