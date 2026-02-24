package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Client for the unified MCP Gateway Lambda.
 *
 * <p>Calls the MCP Gateway via HTTP Function URL, which provides access to:
 * <ul>
 *   <li>context7 - Documentation lookup</li>
 *   <li>github - GitHub repository operations</li>
 *   <li>jira - Jira issue tracking</li>
 * </ul>
 *
 * <p>Implements {@link ToolProvider} to integrate with the agent's tool registry.
 */
@Slf4j
public class McpGatewayClient implements ToolProvider {

    private final String gatewayUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String namespaceId;

    // Cache for tools list
    private List<Tool> cachedTools;

    public McpGatewayClient(String gatewayUrl, String namespace, ObjectMapper objectMapper) {
        this.gatewayUrl = gatewayUrl.endsWith("/") ? gatewayUrl.substring(0, gatewayUrl.length() - 1) : gatewayUrl;
        this.namespaceId = namespace;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String namespace() {
        return namespaceId;
    }

    @Override
    public List<Tool> toolDefinitions() {
        if (cachedTools != null) {
            return cachedTools;
        }

        try {
            String url = gatewayUrl + "/" + namespaceId + "/tools";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Failed to list tools from gateway: {} - {}", response.statusCode(), response.body());
                return List.of();
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode toolsNode = json.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                return List.of();
            }

            List<Tool> tools = new ArrayList<>();
            for (JsonNode toolNode : toolsNode) {
                String toolName = namespaceId + "_" + toolNode.get("name").asText();
                String description = toolNode.has("description") ? toolNode.get("description").asText() : "";
                JsonNode schemaNode = toolNode.get("inputSchema");

                @SuppressWarnings("unchecked")
                Map<String, Object> inputSchema = schemaNode != null
                        ? objectMapper.convertValue(schemaNode, Map.class)
                        : Map.of("type", "object", "properties", Map.of());

                tools.add(new Tool(toolName, description, inputSchema));
            }

            cachedTools = tools;
            log.info("Loaded {} tools from MCP Gateway namespace '{}'", tools.size(), namespaceId);
            return tools;

        } catch (Exception e) {
            log.error("Failed to list tools from MCP Gateway namespace '{}': {}", namespaceId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        // Strip namespace prefix if present
        String toolName = call.name();
        String toolUseId = call.id();
        String actualToolName = toolName.startsWith(namespaceId + "_")
                ? toolName.substring(namespaceId.length() + 1)
                : toolName;

        try {
            String url = gatewayUrl + "/" + namespaceId + "/call";

            // Convert JsonNode input to Map
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = call.input() != null
                    ? objectMapper.convertValue(call.input(), Map.class)
                    : Map.of();

            Map<String, Object> requestBody = Map.of(
                    "tool", actualToolName,
                    "arguments", arguments
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Tool call failed: {} - {}", response.statusCode(), response.body());
                return ToolResult.error(toolUseId, "MCP Gateway error: " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode resultNode = json.get("result");

            if (resultNode == null) {
                return ToolResult.error(toolUseId, "No result from MCP Gateway");
            }

            // Check for error
            if (resultNode.has("isError") && resultNode.get("isError").asBoolean()) {
                JsonNode contentNode = resultNode.get("content");
                if (contentNode != null && contentNode.isArray() && contentNode.size() > 0) {
                    return ToolResult.error(toolUseId, contentNode.get(0).get("text").asText());
                }
                return ToolResult.error(toolUseId, "Tool execution failed");
            }

            // Extract text content
            JsonNode contentNode = resultNode.get("content");
            if (contentNode != null && contentNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : contentNode) {
                    if ("text".equals(item.get("type").asText())) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(item.get("text").asText());
                    }
                }
                return ToolResult.success(toolUseId, sb.toString());
            }

            return ToolResult.success(toolUseId, resultNode.toString());

        } catch (Exception e) {
            log.error("Failed to execute tool '{}' via MCP Gateway: {}", toolName, e.getMessage());
            return ToolResult.error(toolUseId, "MCP Gateway error: " + e.getMessage());
        }
    }

    /**
     * Creates MCP Gateway clients for all available namespaces.
     *
     * @param gatewayUrl   The MCP Gateway Function URL
     * @param objectMapper JSON object mapper
     * @return List of gateway clients, one per namespace
     */
    public static List<McpGatewayClient> createAllClients(String gatewayUrl, ObjectMapper objectMapper) {
        List<McpGatewayClient> clients = new ArrayList<>();

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String url = gatewayUrl.endsWith("/") ? gatewayUrl + "namespaces" : gatewayUrl + "/namespaces";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                JsonNode namespacesNode = json.get("namespaces");
                if (namespacesNode != null && namespacesNode.isArray()) {
                    for (JsonNode ns : namespacesNode) {
                        String namespace = ns.asText();
                        clients.add(new McpGatewayClient(gatewayUrl, namespace, objectMapper));
                        log.info("Created MCP Gateway client for namespace: {}", namespace);
                    }
                }
            } else {
                log.warn("Failed to fetch namespaces from MCP Gateway: {}", response.statusCode());
            }

        } catch (Exception e) {
            log.error("Failed to create MCP Gateway clients: {}", e.getMessage());
        }

        return clients;
    }
}
