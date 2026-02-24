package com.aidriven.lambda.factory;

import com.aidriven.core.config.McpServerConfig;
import com.aidriven.core.service.SecretsService;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.mcp.McpConnectionFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for parsing MCP configurations and creating MCP tool providers.
 */
@Slf4j
public class McpProviderFactory {

    private final ObjectMapper objectMapper;
    private final McpConnectionFactory connectionFactory;

    public McpProviderFactory(ObjectMapper objectMapper, SecretsService secretsService) {
        this.objectMapper = objectMapper;
        this.connectionFactory = new McpConnectionFactory(secretsService);
    }

    public McpConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    /**
     * Creates MCP tool providers from configuration string.
     * Connects to each enabled MCP server and discovers tools.
     *
     * @param configJson The JSON configuration of MCP servers
     * @return List of MCP bridge tool providers (may be empty if none configured)
     */
    public List<McpBridgeToolProvider> createProviders(String configJson) {
        List<McpBridgeToolProvider> providers = new ArrayList<>();

        if (configJson == null || configJson.isBlank() || "[]".equals(configJson.trim())) {
            return providers;
        }

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
                    // Continue with remaining servers — one failure shouldn't block all
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse MCP_SERVERS_CONFIG: {}", e.getMessage(), e);
        }

        return providers;
    }
}
