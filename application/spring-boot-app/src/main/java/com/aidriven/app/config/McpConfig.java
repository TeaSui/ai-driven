package com.aidriven.app.config;

import com.aidriven.core.config.McpServerConfig;
import com.aidriven.core.service.SecretsService;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.mcp.McpConnectionFactory;
import com.aidriven.mcp.McpGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP (Model Context Protocol) integration beans.
 *
 * <p>Replaces {@code McpProviderFactory} and the MCP wiring in {@code ServiceFactory}.
 * Supports two modes:
 * <ul>
 *   <li><b>Legacy stdio/http</b> — parsed from {@code MCP_SERVERS_CONFIG} JSON</li>
 *   <li><b>Gateway</b> — unified gateway URL routing all MCP calls through a single endpoint</li>
 * </ul>
 *
 * <p>When {@code ai-driven.mcp.gateway-enabled=true}, the legacy providers are skipped
 * and gateway clients are created instead.
 */
@Slf4j
@Configuration
public class McpConfig {

    @Bean
    McpConnectionFactory mcpConnectionFactory(SecretsService secretsService) {
        return new McpConnectionFactory(secretsService);
    }

    /**
     * Legacy MCP tool providers parsed from JSON configuration.
     * Returns empty list when the MCP Gateway is enabled.
     */
    @Bean
    List<McpBridgeToolProvider> mcpToolProviders(McpConnectionFactory connectionFactory,
                                                  ObjectMapper objectMapper,
                                                  AppProperties properties) {
        AppProperties.McpProperties mcp = properties.mcp();
        if (mcp != null && mcp.gatewayEnabled()) {
            log.info("MCP Gateway enabled, skipping legacy stdio/http providers");
            return List.of();
        }

        String configJson = mcp != null ? mcp.serversConfig() : null;
        if (configJson == null || configJson.isBlank() || "[]".equals(configJson.trim())) {
            return List.of();
        }

        List<McpBridgeToolProvider> providers = new ArrayList<>();
        try {
            McpServerConfig[] configs = objectMapper.readValue(configJson, McpServerConfig[].class);
            for (McpServerConfig config : configs) {
                if (!config.enabled()) {
                    log.info("MCP server '{}' is disabled, skipping", config.namespace());
                    continue;
                }
                try {
                    McpBridgeToolProvider provider = connectionFactory.createProvider(config);
                    providers.add(provider);
                    log.info("Registered MCP tool provider: {}", provider);
                } catch (Exception e) {
                    log.error("Failed to connect MCP server '{}': {}", config.namespace(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse MCP servers config: {}", e.getMessage(), e);
        }
        return providers;
    }

    /**
     * MCP Gateway clients for all available namespaces.
     * Returns empty list when the MCP Gateway is disabled.
     */
    @Bean
    List<McpGatewayClient> mcpGatewayClients(ObjectMapper objectMapper, AppProperties properties) {
        AppProperties.McpProperties mcp = properties.mcp();
        if (mcp == null || !mcp.gatewayEnabled()) {
            return List.of();
        }

        String gatewayUrl = mcp.gatewayUrl();
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            log.warn("MCP Gateway enabled but no gateway URL configured");
            return List.of();
        }

        return McpGatewayClient.createAllClients(gatewayUrl, objectMapper);
    }
}
