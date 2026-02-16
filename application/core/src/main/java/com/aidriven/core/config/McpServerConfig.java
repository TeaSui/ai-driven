package com.aidriven.core.config;

import java.util.Map;

/**
 * Configuration for a single MCP server connection.
 *
 * <p>Each MCP server maps to one namespace in the ToolRegistry.
 * Transport determines how we connect: stdio (child process) or http (remote server).</p>
 *
 * <p>Example JSON (from MCP_SERVERS_CONFIG env var):
 * <pre>{@code
 * {
 *   "namespace": "monitoring",
 *   "transport": "stdio",
 *   "command": "npx @datadog/mcp-server",
 *   "env": {"DD_API_KEY_SECRET_ARN": "arn:aws:..."},
 *   "secretArn": "arn:aws:secretsmanager:...",
 *   "enabled": true
 * }
 * }</pre>
 *
 * @param namespace  Tool namespace prefix (e.g., "monitoring", "messaging")
 * @param transport  Connection transport: "stdio" or "http"
 * @param command    For stdio: the command to launch the MCP server process
 * @param args       For stdio: optional command arguments
 * @param url        For http: the MCP server URL
 * @param env        Environment variables to pass to the MCP server process
 * @param secretArn  AWS Secrets Manager ARN for credentials (resolved at runtime)
 * @param enabled    Feature flag to enable/disable this server
 */
public record McpServerConfig(
        String namespace,
        String transport,
        String command,
        String[] args,
        String url,
        Map<String, String> env,
        String secretArn,
        boolean enabled) {

    /** Validates that required fields are present based on transport type. */
    public void validate() {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("MCP server config: namespace is required");
        }
        if (transport == null || transport.isBlank()) {
            throw new IllegalArgumentException("MCP server config: transport is required for " + namespace);
        }
        if ("stdio".equalsIgnoreCase(transport)) {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException(
                        "MCP server config: command is required for stdio transport (" + namespace + ")");
            }
        } else if ("http".equalsIgnoreCase(transport) || "sse".equalsIgnoreCase(transport)) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException(
                        "MCP server config: url is required for http/sse transport (" + namespace + ")");
            }
        } else {
            throw new IllegalArgumentException(
                    "MCP server config: unsupported transport '" + transport + "' for " + namespace);
        }
    }

    public boolean isStdio() {
        return "stdio".equalsIgnoreCase(transport);
    }

    public boolean isHttp() {
        return "http".equalsIgnoreCase(transport) || "sse".equalsIgnoreCase(transport);
    }
}
