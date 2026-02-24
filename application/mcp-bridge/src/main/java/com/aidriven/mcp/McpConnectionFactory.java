package com.aidriven.mcp;

import com.aidriven.core.config.McpServerConfig;
import com.aidriven.core.service.SecretsService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.io.File;
import java.util.*;

/**
 * Factory for creating MCP client connections from configuration.
 *
 * <p>
 * Supports two transports:
 * </p>
 * <ul>
 * <li><b>stdio</b>: Launches MCP server as a child process (for bundled/local
 * servers like npx)</li>
 * <li><b>http/sse</b>: Connects to a remote MCP server via HTTP + SSE</li>
 * </ul>
 *
 * <p>
 * Connection lifecycle:
 * </p>
 * <ol>
 * <li>Create transport from config</li>
 * <li>Build McpSyncClient with client info</li>
 * <li>Initialize connection (handshake)</li>
 * <li>Return ready-to-use client</li>
 * </ol>
 *
 * <p>
 * For Lambda: connections are created on cold start and reused across
 * invocations.
 * If a connection dies, the factory can reconnect.
 * </p>
 */
@Slf4j
public class McpConnectionFactory {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(60);
    private static final String CLIENT_NAME = "ai-driven-agent";
    private static final String CLIENT_VERSION = "1.0.0";

    private final SecretsService secretsService;

    public McpConnectionFactory(SecretsService secretsService) {
        this.secretsService = secretsService;
    }

    /**
     * Creates and initializes an MCP client connection from configuration.
     *
     * @param config MCP server configuration
     * @return Connected and initialized McpSyncClient
     * @throws McpConnectionException if connection or initialization fails
     */
    public McpSyncClient connect(McpServerConfig config) {
        log.info("Connecting to MCP server: namespace={} transport={}", config.namespace(), config.transport());

        try {
            config.validate();
            // Resolve secrets if ARN provided
            Map<String, String> resolvedEnv = resolveEnvironment(config);

            McpSyncClient client;

            if (config.isStdio()) {
                client = createStdioClient(config, resolvedEnv);
            } else if (config.isHttp()) {
                client = createHttpClient(config, resolvedEnv);
            } else {
                throw new McpConnectionException(
                        "Unsupported transport: " + config.transport() + " for " + config.namespace());
            }

            // Initialize the connection (MCP handshake)
            McpSchema.InitializeResult initResult = client.initialize();
            log.info("MCP server '{}' initialized: name={} version={} capabilities={}",
                    config.namespace(),
                    initResult.serverInfo().name(),
                    initResult.serverInfo().version(),
                    initResult.capabilities());

            return client;

        } catch (McpConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new McpConnectionException(
                    "Failed to connect to MCP server '" + config.namespace() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Creates an MCP client using stdio transport (child process).
     */
    private McpSyncClient createStdioClient(McpServerConfig config, Map<String, String> env) {
        log.info("Creating stdio MCP client for '{}': command={}", config.namespace(), config.command());

        // Build command + args
        List<String> command = new ArrayList<>();
        command.add(config.command());
        if (config.args() != null) {
            command.addAll(Arrays.asList(config.args()));
        }

        // Resolve paths if in Lambda
        String resolvedCommand = resolvePath(command.get(0));
        String[] resolvedArgs = command.size() > 1
                ? command.subList(1, command.size()).stream().map(this::resolvePath).toArray(String[]::new)
                : new String[0];

        ServerParameters serverParams = ServerParameters.builder(resolvedCommand)
                .args(resolvedArgs)
                .env(env)
                .build();

        StdioClientTransport transport = new StdioClientTransport(serverParams);

        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .requestTimeout(DEFAULT_REQUEST_TIMEOUT)
                .build();
    }

    /**
     * Creates an MCP client using HTTP+SSE transport (remote server).
     * Resolved env vars with keys AUTHORIZATION and API_KEY are injected as HTTP
     * headers.
     */
    private McpSyncClient createHttpClient(McpServerConfig config, Map<String, String> env) {
        String baseUrl = config.url();
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        log.info("Creating HTTP/SSE MCP client for '{}': url={}", config.namespace(), baseUrl);

        // Build custom headers from resolved env (e.g., Authorization tokens from
        // Secrets Manager)
        java.net.http.HttpClient.Builder clientBuilder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(DEFAULT_INIT_TIMEOUT);

        // Inject auth headers via a request customizer
        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder();
        if (env.containsKey("AUTHORIZATION")) {
            requestBuilder.header("Authorization", env.get("AUTHORIZATION"));
        }
        if (env.containsKey("API_KEY")) {
            requestBuilder.header("X-API-Key", env.get("API_KEY"));
        }

        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUrl)
                .clientBuilder(clientBuilder)
                .requestBuilder(requestBuilder)
                .build();

        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .requestTimeout(DEFAULT_REQUEST_TIMEOUT)
                .build();
    }

    /**
     * Resolves a path relative to LAMBDA_TASK_ROOT if running in AWS Lambda.
     */
    private String resolvePath(String path) {
        if (path == null || path.startsWith("-") || path.startsWith("/") || path.contains(":\\")) {
            return path;
        }

        String taskRoot = System.getenv("LAMBDA_TASK_ROOT");
        if (taskRoot != null && !taskRoot.isBlank()) {
            File file = new File(taskRoot, path);
            if (file.exists()) {
                log.debug("Resolved relative path '{}' to '{}'", path, file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }
        return path;
    }

    /**
     * Resolves environment variables, including secrets from AWS Secrets Manager.
     *
     * <p>
     * If the config has a secretArn, the secret JSON is fetched and merged
     * with any static env vars from the config. Secret values take precedence.
     * </p>
     */
    private Map<String, String> resolveEnvironment(McpServerConfig config) {
        // Start with system env to ensure child processes inherit Lambda environment
        Map<String, String> env = new HashMap<>(System.getenv());

        // Overlay with static env from config
        if (config.env() != null) {
            env.putAll(config.env());
        }

        // Overlay secrets from Secrets Manager
        if (config.secretArn() != null && !config.secretArn().isBlank() && secretsService != null) {
            try {
                Map<String, Object> secretJson = secretsService.getSecretJson(config.secretArn());
                if (secretJson != null) {
                    secretJson.forEach((k, v) -> {
                        if (v != null) {
                            String value = v.toString();
                            env.put(k, value);
                            // Map to expected MCP server env vars
                            mapSecretToEnv(k, value, env);
                        }
                    });
                    log.info("Resolved {} secrets for MCP server '{}'", secretJson.size(), config.namespace());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve secrets for MCP server '{}': {}",
                        config.namespace(), e.getMessage());
                // Continue without secrets — the MCP server may not need them
            }
        }

        return env;
    }

    private void mapSecretToEnv(String key, String value, Map<String, String> env) {
        switch (key) {
            case "baseUrl" -> env.put("JIRA_BASE_URL", value);
            case "apiToken" -> env.put("JIRA_API_TOKEN", value);
            case "email" -> env.put("JIRA_EMAIL", value);
            case "owner" -> env.put("GITHUB_OWNER", value);
            case "repo" -> env.put("GITHUB_REPO", value);
            case "token" -> env.put("GITHUB_TOKEN", value);
        }
    }

    /**
     * Convenience: connects to an MCP server and wraps it as a
     * {@link McpBridgeToolProvider}.
     * This keeps {@link McpSyncClient} internal to the mcp-bridge module, so
     * consumers
     * (e.g., lambda-handlers) don't need the MCP SDK on their classpath.
     *
     * @param config MCP server configuration
     * @return Ready-to-use McpBridgeToolProvider
     * @throws McpConnectionException if connection fails
     */
    public McpBridgeToolProvider createProvider(McpServerConfig config) {
        McpSyncClient client = connect(config);
        return new McpBridgeToolProvider(config.namespace(), client);
    }

    /**
     * Safely closes an MCP client connection.
     *
     * @param client    The client to close
     * @param namespace The namespace (for logging)
     */
    public static void closeQuietly(McpSyncClient client, String namespace) {
        if (client != null) {
            try {
                client.close();
                log.info("Closed MCP connection for '{}'", namespace);
            } catch (Exception e) {
                log.warn("Error closing MCP connection for '{}': {}", namespace, e.getMessage());
            }
        }
    }

    /**
     * Exception thrown when MCP connection or initialization fails.
     */
    public static class McpConnectionException extends RuntimeException {
        public McpConnectionException(String message) {
            super(message);
        }

        public McpConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
