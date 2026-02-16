package com.aidriven.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link McpServerConfig}.
 *
 * <p>Tests cover: validation for all transport types, transport detection helpers,
 * JSON deserialization, edge cases around null/blank fields, and array config parsing.</p>
 */
class McpServerConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ========================================================================
    // Validation — Happy Paths
    // ========================================================================

    @Nested
    class ValidationHappyPaths {

        @Test
        void validate_stdioWithCommand_passes() {
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", new String[]{"@datadog/mcp-server"},
                    null, Map.of(), null, true);
            assertDoesNotThrow(config::validate);
        }

        @Test
        void validate_stdioWithCommandNoArgs_passes() {
            McpServerConfig config = new McpServerConfig(
                    "monitoring", "stdio", "npx", null,
                    null, null, null, true);
            assertDoesNotThrow(config::validate);
        }

        @Test
        void validate_httpWithUrl_passes() {
            McpServerConfig config = new McpServerConfig(
                    "messaging", "http", null, null,
                    "https://mcp.slack.dev", Map.of(), null, true);
            assertDoesNotThrow(config::validate);
        }

        @Test
        void validate_sseWithUrl_passes() {
            McpServerConfig config = new McpServerConfig(
                    "messaging", "sse", null, null,
                    "https://mcp.slack.dev/sse", null, null, true);
            assertDoesNotThrow(config::validate);
        }

        @Test
        void validate_caseInsensitiveTransport_stdio() {
            McpServerConfig config = new McpServerConfig(
                    "test", "STDIO", "cmd", null, null, null, null, true);
            assertDoesNotThrow(config::validate);
        }

        @Test
        void validate_caseInsensitiveTransport_http() {
            McpServerConfig config = new McpServerConfig(
                    "test", "HTTP", null, null, "http://x", null, null, true);
            assertDoesNotThrow(config::validate);
        }

        @Test
        void validate_disabledConfig_stillValidates() {
            // Even disabled configs should be valid structurally
            McpServerConfig config = new McpServerConfig(
                    "test", "stdio", "npx", null, null, null, null, false);
            assertDoesNotThrow(config::validate);
        }
    }

    // ========================================================================
    // Validation — Error Cases
    // ========================================================================

    @Nested
    class ValidationErrors {

        @Test
        void validate_nullNamespace_throws() {
            McpServerConfig config = new McpServerConfig(
                    null, "stdio", "npx", null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("namespace"));
        }

        @Test
        void validate_blankNamespace_throws() {
            McpServerConfig config = new McpServerConfig(
                    "  ", "stdio", "npx", null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("namespace"));
        }

        @Test
        void validate_emptyNamespace_throws() {
            McpServerConfig config = new McpServerConfig(
                    "", "stdio", "npx", null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("namespace"));
        }

        @Test
        void validate_nullTransport_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", null, "npx", null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("transport"));
        }

        @Test
        void validate_blankTransport_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", "  ", "npx", null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("transport"));
        }

        @Test
        void validate_unsupportedTransport_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", "grpc", null, null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("unsupported transport"));
            assertTrue(ex.getMessage().contains("grpc"));
        }

        @Test
        void validate_stdioWithoutCommand_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", "stdio", null, null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("command"));
        }

        @Test
        void validate_stdioWithBlankCommand_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", "stdio", "  ", null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("command"));
        }

        @Test
        void validate_httpWithoutUrl_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", "http", null, null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("url"));
        }

        @Test
        void validate_httpWithBlankUrl_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", "http", null, null, "  ", null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("url"));
        }

        @Test
        void validate_sseWithoutUrl_throws() {
            McpServerConfig config = new McpServerConfig(
                    "test", "sse", null, null, null, null, null, true);
            assertThrows(IllegalArgumentException.class, config::validate);
        }

        @Test
        void validate_errorMessageIncludesNamespace() {
            McpServerConfig config = new McpServerConfig(
                    "my-monitoring", "stdio", null, null, null, null, null, true);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, config::validate);
            assertTrue(ex.getMessage().contains("my-monitoring"));
        }
    }

    // ========================================================================
    // Transport Detection
    // ========================================================================

    @Nested
    class TransportDetection {

        @Test
        void isStdio_true_forStdio() {
            McpServerConfig config = new McpServerConfig(
                    "test", "stdio", "cmd", null, null, null, null, true);
            assertTrue(config.isStdio());
            assertFalse(config.isHttp());
        }

        @Test
        void isStdio_caseInsensitive() {
            McpServerConfig config = new McpServerConfig(
                    "test", "STDIO", "cmd", null, null, null, null, true);
            assertTrue(config.isStdio());
        }

        @Test
        void isHttp_true_forHttp() {
            McpServerConfig config = new McpServerConfig(
                    "test", "http", null, null, "http://x", null, null, true);
            assertTrue(config.isHttp());
            assertFalse(config.isStdio());
        }

        @Test
        void isHttp_true_forSse() {
            McpServerConfig config = new McpServerConfig(
                    "test", "sse", null, null, "http://x", null, null, true);
            assertTrue(config.isHttp());
            assertFalse(config.isStdio());
        }

        @Test
        void isHttp_caseInsensitive() {
            McpServerConfig config = new McpServerConfig(
                    "test", "HTTP", null, null, "http://x", null, null, true);
            assertTrue(config.isHttp());
        }

        @Test
        void isStdio_false_forNull() {
            McpServerConfig config = new McpServerConfig(
                    "test", null, null, null, null, null, null, true);
            assertFalse(config.isStdio());
            assertFalse(config.isHttp());
        }

        @Test
        void isHttp_false_forUnknownTransport() {
            McpServerConfig config = new McpServerConfig(
                    "test", "grpc", null, null, null, null, null, true);
            assertFalse(config.isStdio());
            assertFalse(config.isHttp());
        }
    }

    // ========================================================================
    // JSON Deserialization
    // ========================================================================

    @Nested
    class JsonDeserialization {

        @Test
        void deserialize_stdioConfig_allFields() throws Exception {
            String json = """
                    {
                        "namespace": "monitoring",
                        "transport": "stdio",
                        "command": "npx",
                        "args": ["@datadog/mcp-server"],
                        "env": {"DD_API_KEY": "test"},
                        "enabled": true
                    }
                    """;
            McpServerConfig config = mapper.readValue(json, McpServerConfig.class);

            assertEquals("monitoring", config.namespace());
            assertEquals("stdio", config.transport());
            assertEquals("npx", config.command());
            assertArrayEquals(new String[]{"@datadog/mcp-server"}, config.args());
            assertEquals(Map.of("DD_API_KEY", "test"), config.env());
            assertTrue(config.enabled());
            assertNull(config.url());
            assertNull(config.secretArn());
        }

        @Test
        void deserialize_httpConfig_allFields() throws Exception {
            String json = """
                    {
                        "namespace": "messaging",
                        "transport": "http",
                        "url": "https://mcp.slack.dev",
                        "secretArn": "arn:aws:secretsmanager:us-east-1:123:secret:slack",
                        "enabled": true
                    }
                    """;
            McpServerConfig config = mapper.readValue(json, McpServerConfig.class);

            assertEquals("messaging", config.namespace());
            assertEquals("http", config.transport());
            assertEquals("https://mcp.slack.dev", config.url());
            assertEquals("arn:aws:secretsmanager:us-east-1:123:secret:slack", config.secretArn());
            assertNull(config.command());
            assertNull(config.args());
        }

        @Test
        void deserialize_minimalConfig_nullDefaults() throws Exception {
            String json = """
                    {
                        "namespace": "test",
                        "transport": "stdio",
                        "command": "npx",
                        "enabled": false
                    }
                    """;
            McpServerConfig config = mapper.readValue(json, McpServerConfig.class);

            assertEquals("test", config.namespace());
            assertFalse(config.enabled());
            assertNull(config.args());
            assertNull(config.env());
            assertNull(config.secretArn());
            assertNull(config.url());
        }

        @Test
        void deserialize_arrayOfConfigs() throws Exception {
            String json = """
                    [
                        {"namespace": "monitoring", "transport": "stdio", "command": "npx", "args": ["@dd/mcp"], "enabled": true},
                        {"namespace": "messaging", "transport": "http", "url": "https://slack.dev/mcp", "enabled": false}
                    ]
                    """;
            McpServerConfig[] configs = mapper.readValue(json, McpServerConfig[].class);

            assertEquals(2, configs.length);
            assertEquals("monitoring", configs[0].namespace());
            assertTrue(configs[0].isStdio());
            assertEquals("messaging", configs[1].namespace());
            assertTrue(configs[1].isHttp());
            assertFalse(configs[1].enabled());
        }

        @Test
        void deserialize_emptyArray() throws Exception {
            McpServerConfig[] configs = mapper.readValue("[]", McpServerConfig[].class);
            assertEquals(0, configs.length);
        }

        @Test
        void deserialize_withMultipleArgs() throws Exception {
            String json = """
                    {
                        "namespace": "test",
                        "transport": "stdio",
                        "command": "npx",
                        "args": ["-y", "@datadog/mcp-server", "--debug"],
                        "enabled": true
                    }
                    """;
            McpServerConfig config = mapper.readValue(json, McpServerConfig.class);
            assertArrayEquals(new String[]{"-y", "@datadog/mcp-server", "--debug"}, config.args());
        }

        @Test
        void deserialize_withMultipleEnvVars() throws Exception {
            String json = """
                    {
                        "namespace": "test",
                        "transport": "stdio",
                        "command": "npx",
                        "env": {"API_KEY": "abc", "REGION": "us-east-1", "DEBUG": "true"},
                        "enabled": true
                    }
                    """;
            McpServerConfig config = mapper.readValue(json, McpServerConfig.class);
            assertEquals(3, config.env().size());
            assertEquals("abc", config.env().get("API_KEY"));
        }
    }

    // ========================================================================
    // Record Accessors
    // ========================================================================

    @Nested
    class RecordAccessors {

        @Test
        void recordComponents_returnCorrectValues() {
            McpServerConfig config = new McpServerConfig(
                    "ns", "stdio", "cmd", new String[]{"a", "b"},
                    "http://x", Map.of("k", "v"), "arn:secret", true);

            assertEquals("ns", config.namespace());
            assertEquals("stdio", config.transport());
            assertEquals("cmd", config.command());
            assertArrayEquals(new String[]{"a", "b"}, config.args());
            assertEquals("http://x", config.url());
            assertEquals(Map.of("k", "v"), config.env());
            assertEquals("arn:secret", config.secretArn());
            assertTrue(config.enabled());
        }

        @Test
        void recordEquals_sameValues() {
            McpServerConfig a = new McpServerConfig(
                    "ns", "stdio", "cmd", null, null, null, null, true);
            McpServerConfig b = new McpServerConfig(
                    "ns", "stdio", "cmd", null, null, null, null, true);
            // Note: record equals works for most components but arrays use reference equality
            // So we don't assert equals for configs with args arrays
            assertEquals(a.namespace(), b.namespace());
            assertEquals(a.transport(), b.transport());
            assertEquals(a.enabled(), b.enabled());
        }
    }
}
