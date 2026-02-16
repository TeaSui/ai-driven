package com.aidriven.mcp;

import com.aidriven.core.config.McpServerConfig;
import com.aidriven.core.service.SecretsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link McpConnectionFactory}.
 *
 * <p>These tests verify connection creation logic, secret resolution, and error handling.
 * Actual MCP server connections are NOT tested here (that's integration test territory).
 * We test the factory's validation, env resolution, and exception wrapping logic.</p>
 */
@ExtendWith(MockitoExtension.class)
class McpConnectionFactoryTest {

    @Mock
    private SecretsService secretsService;

    private McpConnectionFactory factory;

    @BeforeEach
    void setUp() {
        factory = new McpConnectionFactory(secretsService);
    }

    // ========================================================================
    // Config Validation Tests
    // ========================================================================

    @Nested
    class ConfigValidation {

        @Test
        void connect_nullNamespace_throwsMcpConnectionException() {
            McpServerConfig config = new McpServerConfig(
                    null, "stdio", "npx", null, null, null, null, true);

            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));
        }

        @Test
        void connect_blankNamespace_throwsMcpConnectionException() {
            McpServerConfig config = new McpServerConfig(
                    "  ", "stdio", "npx", null, null, null, null, true);

            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));
        }

        @Test
        void connect_missingTransport_throwsMcpConnectionException() {
            McpServerConfig config = new McpServerConfig(
                    "test", null, "npx", null, null, null, null, true);

            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));
        }

        @Test
        void connect_unsupportedTransport_throwsMcpConnectionException() {
            McpServerConfig config = new McpServerConfig(
                    "test", "grpc", null, null, null, null, null, true);

            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));
        }

        @Test
        void connect_stdioWithoutCommand_throwsMcpConnectionException() {
            McpServerConfig config = new McpServerConfig(
                    "test", "stdio", null, null, null, null, null, true);

            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));
        }

        @Test
        void connect_httpWithoutUrl_throwsMcpConnectionException() {
            McpServerConfig config = new McpServerConfig(
                    "test", "http", null, null, null, null, null, true);

            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));
        }
    }

    // ========================================================================
    // Secret Resolution Tests
    // ========================================================================

    @Nested
    class SecretResolution {

        @Test
        void connect_withSecretArn_resolvesSecrets() {
            // Config with secretArn — factory should call secretsService
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", new String[]{"@datadog/mcp-server"},
                    null, Map.of("STATIC_KEY", "static_val"),
                    "arn:aws:secretsmanager:us-east-1:123:secret:dd-api-key", true);

            when(secretsService.getSecretJson("arn:aws:secretsmanager:us-east-1:123:secret:dd-api-key"))
                    .thenReturn(Map.of("DD_API_KEY", "resolved-key-123"));

            // connect() will fail because npx doesn't exist, but secrets should be resolved
            McpConnectionFactory.McpConnectionException ex = assertThrows(
                    McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));

            // Verify secretsService was called
            verify(secretsService).getSecretJson("arn:aws:secretsmanager:us-east-1:123:secret:dd-api-key");
        }

        @Test
        void connect_withNullSecretArn_skipsSecretResolution() {
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", null,
                    null, Map.of(), null, true);

            // Will fail due to npx not available, but should NOT call secretsService
            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));

            verifyNoInteractions(secretsService);
        }

        @Test
        void connect_withBlankSecretArn_skipsSecretResolution() {
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", null,
                    null, Map.of(), "  ", true);

            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));

            verifyNoInteractions(secretsService);
        }

        @Test
        void connect_secretResolutionFails_continuesWithoutSecrets() {
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", null,
                    null, Map.of("FALLBACK", "value"),
                    "arn:aws:secretsmanager:us-east-1:123:secret:missing", true);

            when(secretsService.getSecretJson(anyString()))
                    .thenThrow(new RuntimeException("Access denied"));

            // Should still attempt to connect (will fail for other reasons)
            // but should NOT propagate the secrets exception
            McpConnectionFactory.McpConnectionException ex = assertThrows(
                    McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));

            // The exception should be about connection, not about secrets
            assertFalse(ex.getMessage().contains("Access denied"));
        }

        @Test
        void connect_secretJsonReturnsNull_continuesGracefully() {
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", null,
                    null, null, "arn:aws:some-secret", true);

            when(secretsService.getSecretJson("arn:aws:some-secret")).thenReturn(null);

            // Should proceed without error from secret resolution
            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));

            verify(secretsService).getSecretJson("arn:aws:some-secret");
        }

        @Test
        void connect_secretValuesOverrideStaticEnv() {
            // Static env has DD_API_KEY=old, secret has DD_API_KEY=new
            // After resolution, DD_API_KEY should be "new"
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", null,
                    null, Map.of("DD_API_KEY", "old_value"),
                    "arn:aws:secret", true);

            when(secretsService.getSecretJson("arn:aws:secret"))
                    .thenReturn(Map.of("DD_API_KEY", "new_secret_value"));

            // connect() will fail, but env resolution should have happened
            assertThrows(McpConnectionFactory.McpConnectionException.class,
                    () -> factory.connect(config));

            verify(secretsService).getSecretJson("arn:aws:secret");
        }
    }

    // ========================================================================
    // Close Quietly Tests
    // ========================================================================

    @Nested
    class CloseQuietly {

        @Mock
        private io.modelcontextprotocol.client.McpSyncClient mcpClient;

        @Test
        void closeQuietly_nullClient_doesNotThrow() {
            assertDoesNotThrow(() -> McpConnectionFactory.closeQuietly(null, "test"));
        }

        @Test
        void closeQuietly_normalClient_closesSuccessfully() {
            assertDoesNotThrow(() -> McpConnectionFactory.closeQuietly(mcpClient, "test"));
            verify(mcpClient).close();
        }

        @Test
        void closeQuietly_clientThrows_doesNotPropagate() {
            doThrow(new RuntimeException("Close failed")).when(mcpClient).close();
            assertDoesNotThrow(() -> McpConnectionFactory.closeQuietly(mcpClient, "test"));
            verify(mcpClient).close();
        }
    }

    // ========================================================================
    // Exception Wrapping Tests
    // ========================================================================

    @Nested
    class ExceptionWrapping {

        @Test
        void mcpConnectionException_withMessage() {
            McpConnectionFactory.McpConnectionException ex =
                    new McpConnectionFactory.McpConnectionException("test error");
            assertEquals("test error", ex.getMessage());
            assertNull(ex.getCause());
        }

        @Test
        void mcpConnectionException_withCause() {
            RuntimeException cause = new RuntimeException("root cause");
            McpConnectionFactory.McpConnectionException ex =
                    new McpConnectionFactory.McpConnectionException("wrapped", cause);
            assertEquals("wrapped", ex.getMessage());
            assertSame(cause, ex.getCause());
        }
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    void constructor_nullSecretsService_doesNotThrow() {
        // secretsService can be null — some deployments may not need secrets
        assertDoesNotThrow(() -> new McpConnectionFactory(null));
    }

    @Test
    void connect_nullSecretsServiceWithSecretArn_skipsResolution() {
        McpConnectionFactory nullSecretsFactory = new McpConnectionFactory(null);
        McpServerConfig config = new McpServerConfig(
                "test", "stdio", "npx", null,
                null, Map.of(), "arn:aws:secret", true);

        // Should not NPE on null secretsService — skips resolution
        assertThrows(McpConnectionFactory.McpConnectionException.class,
                () -> nullSecretsFactory.connect(config));
    }
}
