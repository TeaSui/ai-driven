package com.aidriven.core.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentMetrics} CloudWatch Embedded Metric Format (EMF) output.
 */
class AgentMetricsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capturedOutput;

    @BeforeEach
    void setUp() {
        capturedOutput = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOutput));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void flush_outputs_valid_json() throws Exception {
        new AgentMetrics()
                .withTenantId("tenant-123")
                .withPlatform("GITHUB")
                .recordTurns(5)
                .flush();

        String output = capturedOutput.toString().trim();
        assertFalse(output.isEmpty(), "Should output EMF JSON");

        // Should be valid JSON
        JsonNode json = objectMapper.readTree(output);
        assertNotNull(json, "Output should be valid JSON");
    }

    @Test
    void flush_contains_aws_metadata_block() throws Exception {
        new AgentMetrics()
                .withTenantId("tenant-123")
                .recordTurns(3)
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());

        assertTrue(json.has("_aws"), "Should have _aws metadata block");
        JsonNode aws = json.get("_aws");
        assertTrue(aws.has("Timestamp"), "Should have Timestamp");
        assertTrue(aws.has("CloudWatchMetrics"), "Should have CloudWatchMetrics");
    }

    @Test
    void flush_contains_correct_namespace() throws Exception {
        new AgentMetrics()
                .recordTokens(1000)
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());
        JsonNode cloudWatchMetrics = json.get("_aws").get("CloudWatchMetrics").get(0);

        assertEquals("AiAgent", cloudWatchMetrics.get("Namespace").asText());
    }

    @Test
    void flush_includes_all_recorded_metrics() throws Exception {
        new AgentMetrics()
                .withTenantId("t-1")
                .withPlatform("JIRA")
                .recordTurns(5)
                .recordTokens(2500)
                .recordTools(8)
                .recordLatency(1500)
                .recordErrors(1)
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());

        // Check metric values are present as top-level properties
        assertEquals(5, json.get("agent.turns").asInt());
        assertEquals(2500, json.get("agent.tokens.total").asInt());
        assertEquals(8, json.get("agent.tools.count").asInt());
        assertEquals(1500, json.get("agent.latency.ms").asInt());
        assertEquals(1, json.get("agent.errors").asInt());

        // Check dimension values
        assertEquals("t-1", json.get("TenantId").asText());
        assertEquals("JIRA", json.get("Platform").asText());
    }

    @Test
    void flush_includes_metric_definitions_with_units() throws Exception {
        new AgentMetrics()
                .recordTurns(3)
                .recordLatency(500)
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());
        JsonNode metrics = json.get("_aws").get("CloudWatchMetrics").get(0).get("Metrics");

        assertTrue(metrics.isArray(), "Metrics should be an array");
        assertEquals(2, metrics.size(), "Should have 2 metric definitions");

        // Verify at least one metric has correct unit
        boolean hasTurns = false;
        boolean hasLatency = false;
        for (JsonNode metric : metrics) {
            String name = metric.get("Name").asText();
            String unit = metric.get("Unit").asText();
            if ("agent.turns".equals(name)) {
                assertEquals("Count", unit);
                hasTurns = true;
            }
            if ("agent.latency.ms".equals(name)) {
                assertEquals("Milliseconds", unit);
                hasLatency = true;
            }
        }
        assertTrue(hasTurns, "Should have agent.turns metric");
        assertTrue(hasLatency, "Should have agent.latency.ms metric");
    }

    @Test
    void flush_includes_dimensions() throws Exception {
        new AgentMetrics()
                .withTenantId("t-abc")
                .withPlatform("GITHUB")
                .recordTurns(1)
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());
        JsonNode dimensions = json.get("_aws").get("CloudWatchMetrics").get(0).get("Dimensions");

        assertTrue(dimensions.isArray(), "Dimensions should be an array");
        assertEquals(1, dimensions.size(), "Should have 1 dimension set");

        JsonNode dimensionSet = dimensions.get(0);
        assertTrue(dimensionSet.isArray(), "Dimension set should be an array");
        assertTrue(dimensionSet.toString().contains("TenantId"), "Should have TenantId dimension");
        assertTrue(dimensionSet.toString().contains("Platform"), "Should have Platform dimension");
    }

    @Test
    void putProperty_adds_custom_properties() throws Exception {
        new AgentMetrics()
                .putProperty("TicketKey", "PROJ-123")
                .putProperty("AgentVersion", "v1.2.3")
                .recordTurns(1)
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());

        assertEquals("PROJ-123", json.get("TicketKey").asText());
        assertEquals("v1.2.3", json.get("AgentVersion").asText());
    }

    @Test
    void putMetric_adds_custom_metric() throws Exception {
        new AgentMetrics()
                .putMetric("custom.metric", 42.5, "Percent")
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());

        assertEquals(42.5, json.get("custom.metric").asDouble(), 0.01);

        JsonNode metrics = json.get("_aws").get("CloudWatchMetrics").get(0).get("Metrics");
        boolean hasCustomMetric = false;
        for (JsonNode metric : metrics) {
            if ("custom.metric".equals(metric.get("Name").asText())) {
                assertEquals("Percent", metric.get("Unit").asText());
                hasCustomMetric = true;
            }
        }
        assertTrue(hasCustomMetric, "Should have custom.metric definition");
    }

    @Test
    void null_tenant_and_platform_are_ignored() throws Exception {
        new AgentMetrics()
                .withTenantId(null)
                .withPlatform(null)
                .recordTurns(1)
                .flush();

        JsonNode json = objectMapper.readTree(capturedOutput.toString().trim());

        assertFalse(json.has("TenantId"), "Should not include null TenantId");
        assertFalse(json.has("Platform"), "Should not include null Platform");
    }

    @Test
    void builder_methods_return_same_instance_for_chaining() {
        AgentMetrics metrics = new AgentMetrics();

        assertSame(metrics, metrics.withTenantId("t1"));
        assertSame(metrics, metrics.withPlatform("p1"));
        assertSame(metrics, metrics.putProperty("k", "v"));
        assertSame(metrics, metrics.putMetric("m", 1.0, "Count"));
        assertSame(metrics, metrics.recordTurns(1));
        assertSame(metrics, metrics.recordTokens(1));
        assertSame(metrics, metrics.recordTools(1));
        assertSame(metrics, metrics.recordLatency(1));
        assertSame(metrics, metrics.recordErrors(1));
    }
}
