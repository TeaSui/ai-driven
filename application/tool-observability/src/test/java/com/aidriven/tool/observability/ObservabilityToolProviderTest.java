package com.aidriven.tool.observability;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.observability.LogQuery;
import com.aidriven.spi.observability.LogQueryResult;
import com.aidriven.spi.observability.ObservabilityClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aidriven.spi.observability.MetricQuery;
import com.aidriven.spi.observability.MetricQueryResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObservabilityToolProviderTest {

    @Mock
    private ObservabilityClient observabilityClient;

    private ObservabilityToolProvider provider;
    private ObjectMapper objectMapper;
    private OperationContext context;

    @BeforeEach
    void setUp() {
        provider = new ObservabilityToolProvider(observabilityClient);
        objectMapper = new ObjectMapper();
        context = OperationContext.builder().tenantId("test-tenant").build();
    }

    @Test
    void namespace_is_observability() {
        assertEquals("observability", provider.namespace());
    }

    @Test
    void toolDefinitions_exposes_two_tools() {
        List<Tool> tools = provider.toolDefinitions();
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("observability_query_logs")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("observability_query_metrics")));
    }

    @Test
    void query_logs_returns_formatted_output_on_success() throws Exception {
        LogQueryResult result = LogQueryResult.builder()
                .lines(List.of("[2024-01-01T10:00:00Z] ERROR NullPointerException at line 42",
                        "[2024-01-01T10:01:00Z] ERROR Failed to connect to DB"))
                .truncated(false)
                .totalLines(2)
                .build();
        when(observabilityClient.queryLogs(eq(context), any(LogQuery.class))).thenReturn(result);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("log_group_pattern", "/aws/lambda/ai-driven");
        input.put("filter_pattern", "ERROR");
        input.put("start_minutes_ago", 30);

        ToolResult toolResult = provider.execute(context,
                new ToolCall("call-1", "observability_query_logs", input));

        assertFalse(toolResult.isError());
        String content = toolResult.content();
        assertTrue(content.contains("/aws/lambda/ai-driven"), "Should include log group");
        assertTrue(content.contains("NullPointerException"), "Should include log message");
        assertTrue(content.contains("ERROR"), "Should include filter pattern");
    }

    @Test
    void query_logs_passes_correct_parameters_to_client() throws Exception {
        when(observabilityClient.queryLogs(any(), any())).thenReturn(
                LogQueryResult.builder().lines(List.of("log line 1")).truncated(false).totalLines(1).build());

        ObjectNode input = objectMapper.createObjectNode();
        input.put("log_group_pattern", "/aws/lambda/ai-driven");
        input.put("filter_pattern", "Exception");
        input.put("start_minutes_ago", 120);
        input.put("limit", 50);

        provider.execute(context, new ToolCall("call-2", "observability_query_logs", input));

        ArgumentCaptor<LogQuery> captor = ArgumentCaptor.forClass(LogQuery.class);
        verify(observabilityClient).queryLogs(eq(context), captor.capture());

        LogQuery capturedQuery = captor.getValue();
        assertEquals("/aws/lambda/ai-driven", capturedQuery.getLogGroupPattern());
        assertEquals("Exception", capturedQuery.getFilterPattern());
        assertEquals(120, capturedQuery.getStartMinutesAgo());
        assertEquals(50, capturedQuery.getLimit());
    }

    @Test
    void query_logs_returns_no_logs_message_when_empty() throws Exception {
        when(observabilityClient.queryLogs(any(), any())).thenReturn(
                LogQueryResult.builder().lines(List.of()).truncated(false).totalLines(0).build());

        ObjectNode input = objectMapper.createObjectNode();
        input.put("log_group_pattern", "/aws/lambda/ai-driven");

        ToolResult result = provider.execute(context,
                new ToolCall("call-3", "observability_query_logs", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("No log entries found"));
    }

    @Test
    void query_logs_shows_truncation_notice_when_truncated() throws Exception {
        when(observabilityClient.queryLogs(any(), any())).thenReturn(
                LogQueryResult.builder()
                        .lines(List.of("[ts] line 1", "[ts] line 2"))
                        .truncated(true)
                        .totalLines(5000)
                        .build());

        ObjectNode input = objectMapper.createObjectNode();
        input.put("log_group_pattern", "/aws/lambda/ai-driven");

        ToolResult result = provider.execute(context,
                new ToolCall("call-4", "observability_query_logs", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("truncated"), "Should mention truncation");
        assertTrue(result.content().contains("5000"), "Should show total line count");
    }

    @Test
    void query_logs_returns_error_result_on_exception() throws Exception {
        when(observabilityClient.queryLogs(any(), any())).thenThrow(new RuntimeException("AccessDenied"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("log_group_pattern", "/aws/lambda/ai-driven");

        ToolResult result = provider.execute(context,
                new ToolCall("call-5", "observability_query_logs", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("AccessDenied"));
    }

    @Test
    void unknown_action_returns_error() {
        ObjectNode input = objectMapper.createObjectNode();
        ToolResult result = provider.execute(context,
                new ToolCall("call-6", "observability_unknown_action", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown observability action"));
    }

    // ─── query_metrics tests ───

    @Test
    void query_metrics_returns_formatted_output_on_success() throws Exception {
        MetricQueryResult result = MetricQueryResult.builder()
                .metricName("agent.turns")
                .namespace("AiAgent")
                .average(4.5)
                .sum(45.0)
                .minimum(1.0)
                .maximum(10.0)
                .sampleCount(10)
                .unit("Count")
                .build();
        when(observabilityClient.queryMetrics(eq(context), any(MetricQuery.class))).thenReturn(result);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("namespace", "AiAgent");
        input.put("metric_name", "agent.turns");
        input.put("statistic", "Average");

        ToolResult toolResult = provider.execute(context,
                new ToolCall("call-m1", "observability_query_metrics", input));

        assertFalse(toolResult.isError());
        String content = toolResult.content();
        assertTrue(content.contains("agent.turns"), "Should include metric name");
        assertTrue(content.contains("AiAgent"), "Should include namespace");
        assertTrue(content.contains("Average"), "Should include statistic");
    }

    @Test
    void query_metrics_passes_correct_parameters_to_client() throws Exception {
        when(observabilityClient.queryMetrics(any(), any())).thenReturn(
                MetricQueryResult.builder()
                        .metricName("agent.latency.ms")
                        .namespace("AiAgent")
                        .average(150.0)
                        .build());

        ObjectNode input = objectMapper.createObjectNode();
        input.put("namespace", "AiAgent");
        input.put("metric_name", "agent.latency.ms");
        input.put("statistic", "Average");
        input.put("period_minutes", 60);
        input.put("start_minutes_ago", 1440); // 24 hours

        provider.execute(context, new ToolCall("call-m2", "observability_query_metrics", input));

        ArgumentCaptor<MetricQuery> captor = ArgumentCaptor.forClass(MetricQuery.class);
        verify(observabilityClient).queryMetrics(eq(context), captor.capture());

        MetricQuery capturedQuery = captor.getValue();
        assertEquals("AiAgent", capturedQuery.getNamespace());
        assertEquals("agent.latency.ms", capturedQuery.getMetricName());
        assertEquals("Average", capturedQuery.getStatistic());
        assertEquals(60, capturedQuery.getPeriodMinutes());
        assertEquals(1440, capturedQuery.getStartMinutesAgo());
    }

    @Test
    void query_metrics_uses_default_values_when_optional_params_omitted() throws Exception {
        when(observabilityClient.queryMetrics(any(), any())).thenReturn(
                MetricQueryResult.builder()
                        .metricName("agent.errors")
                        .namespace("AiAgent")
                        .sum(5.0)
                        .build());

        ObjectNode input = objectMapper.createObjectNode();
        input.put("namespace", "AiAgent");
        input.put("metric_name", "agent.errors");

        provider.execute(context, new ToolCall("call-m3", "observability_query_metrics", input));

        ArgumentCaptor<MetricQuery> captor = ArgumentCaptor.forClass(MetricQuery.class);
        verify(observabilityClient).queryMetrics(eq(context), captor.capture());

        MetricQuery capturedQuery = captor.getValue();
        assertEquals("Sum", capturedQuery.getStatistic(), "Default statistic should be Sum");
        assertEquals(5, capturedQuery.getPeriodMinutes(), "Default period should be 5 minutes");
        assertEquals(60, capturedQuery.getStartMinutesAgo(), "Default start should be 60 minutes ago");
    }

    @Test
    void query_metrics_returns_error_on_exception() throws Exception {
        when(observabilityClient.queryMetrics(any(), any()))
                .thenThrow(new RuntimeException("InvalidParameterValue"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("namespace", "AiAgent");
        input.put("metric_name", "agent.turns");

        ToolResult result = provider.execute(context,
                new ToolCall("call-m4", "observability_query_metrics", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("InvalidParameterValue"));
    }

    @Test
    void query_metrics_includes_dimension_filter() throws Exception {
        when(observabilityClient.queryMetrics(any(), any())).thenReturn(
                MetricQueryResult.builder()
                        .metricName("agent.tokens.total")
                        .namespace("AiAgent")
                        .sum(50000.0)
                        .build());

        ObjectNode input = objectMapper.createObjectNode();
        input.put("namespace", "AiAgent");
        input.put("metric_name", "agent.tokens.total");
        input.put("dimension_name", "Platform");
        input.put("dimension_value", "GITHUB");

        provider.execute(context, new ToolCall("call-m5", "observability_query_metrics", input));

        ArgumentCaptor<MetricQuery> captor = ArgumentCaptor.forClass(MetricQuery.class);
        verify(observabilityClient).queryMetrics(eq(context), captor.capture());

        MetricQuery capturedQuery = captor.getValue();
        assertEquals("Platform", capturedQuery.getDimensionName());
        assertEquals("GITHUB", capturedQuery.getDimensionValue());
    }
}
