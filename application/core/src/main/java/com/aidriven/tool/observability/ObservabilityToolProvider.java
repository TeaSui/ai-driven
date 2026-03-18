package com.aidriven.tool.observability;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.observability.LogQuery;
import com.aidriven.spi.observability.LogQueryResult;
import com.aidriven.spi.observability.MetricQuery;
import com.aidriven.spi.observability.MetricQueryResult;
import com.aidriven.spi.observability.ObservabilityClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Exposes observability operations (CloudWatch Logs, etc.) as Claude tools.
 */
@Slf4j
public class ObservabilityToolProvider implements ToolProvider {

    private final ObservabilityClient client;

    public ObservabilityToolProvider(ObservabilityClient client) {
        this.client = client;
    }

    @Override
    public String namespace() {
        return "observability";
    }

    @Override
    public List<Tool> toolDefinitions() {
        return List.of(
                Tool.of("observability_query_logs",
                        "Query live CloudWatch production logs using a filter pattern. " +
                                "Use this to investigate errors, exceptions, or anomalies reported in a Jira ticket. " +
                                "Results are returned as timestamped log lines, capped at ~8000 chars.",
                        Map.of(
                                "log_group_pattern", Tool.stringProp(
                                        "CloudWatch log group name or prefix, e.g. '/aws/lambda/ai-driven'"),
                                "filter_pattern", Tool.stringProp(
                                        "Text or regex to filter log messages, e.g. 'ERROR' or 'NullPointerException'"),
                                "start_minutes_ago", Tool.intProp(
                                        "How many minutes ago to start the query window (default: 60)"),
                                "limit", Tool.intProp(
                                        "Maximum log lines to return (default: 100, max: 1000)")),
                        "log_group_pattern"),
                Tool.of("observability_query_metrics",
                        "Query CloudWatch metrics for agent performance statistics. " +
                                "Use this to check agent latency, token usage, error rates, or tool invocation counts. " +
                                "Returns aggregated statistics (average, sum, min, max) over the specified time window.",
                        Map.of(
                                "namespace", Tool.stringProp(
                                        "CloudWatch namespace, e.g. 'AiAgent' or 'AWS/Lambda'"),
                                "metric_name", Tool.stringProp(
                                        "Metric name, e.g. 'agent.turns', 'agent.tokens.total', 'agent.latency.ms'"),
                                "statistic", Tool.stringProp(
                                        "Statistic to retrieve: Average, Sum, Minimum, Maximum, SampleCount (default: Sum)"),
                                "period_minutes", Tool.intProp(
                                        "Aggregation period in minutes (default: 5)"),
                                "start_minutes_ago", Tool.intProp(
                                        "How many minutes ago to start the query window (default: 60)"),
                                "dimension_name", Tool.stringProp(
                                        "Optional dimension name to filter by, e.g. 'Platform' or 'TenantId'"),
                                "dimension_value", Tool.stringProp(
                                        "Optional dimension value to filter by, e.g. 'GITHUB' or 'tenant-123'")),
                        "namespace", "metric_name"));
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        String action = call.name().substring(namespace().length() + 1);
        JsonNode input = call.input();

        try {
            return switch (action) {
                case "query_logs" -> queryLogs(context, call.id(), input);
                case "query_metrics" -> queryMetrics(context, call.id(), input);
                default -> ToolResult.error(call.id(), "Unknown observability action: " + action);
            };
        } catch (Exception e) {
            log.error("Observability tool error: {}", e.getMessage(), e);
            return ToolResult.error(call.id(), "Observability error: " + e.getMessage());
        }
    }

    private ToolResult queryLogs(OperationContext context, String id, JsonNode input) throws Exception {
        String logGroupPattern = input.get("log_group_pattern").asText();
        String filterPattern = input.has("filter_pattern") && !input.get("filter_pattern").isNull()
                ? input.get("filter_pattern").asText()
                : null;
        int startMinutesAgo = input.has("start_minutes_ago") && !input.get("start_minutes_ago").isNull()
                ? input.get("start_minutes_ago").asInt()
                : 60;
        int limit = input.has("limit") && !input.get("limit").isNull()
                ? Math.min(input.get("limit").asInt(), 1000)
                : 100;

        LogQuery query = LogQuery.builder()
                .logGroupPattern(logGroupPattern)
                .filterPattern(filterPattern)
                .startMinutesAgo(startMinutesAgo)
                .limit(limit)
                .build();

        log.info("Querying logs: group={}, filter={}, lookback={}min", logGroupPattern, filterPattern, startMinutesAgo);

        LogQueryResult result = client.queryLogs(context, query);

        if (result.getLines().isEmpty()) {
            return ToolResult.success(id, "No log entries found matching the query in the last " +
                    startMinutesAgo + " minutes.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## CloudWatch Logs — ").append(logGroupPattern).append("\n");
        sb.append("Last ").append(startMinutesAgo).append(" min");
        if (filterPattern != null)
            sb.append(" | filter: `").append(filterPattern).append("`");
        sb.append("\n\n```\n");
        sb.append(String.join("\n", result.getLines()));
        sb.append("\n```\n");

        if (result.isTruncated()) {
            sb.append("\n*Output truncated — ").append(result.getTotalLines())
                    .append(" total lines, showing first ").append(result.getLines().size()).append(".*");
        }

        return ToolResult.success(id, sb.toString());
    }

    private ToolResult queryMetrics(OperationContext context, String id, JsonNode input) throws Exception {
        String namespace = input.get("namespace").asText();
        String metricName = input.get("metric_name").asText();
        String statistic = input.has("statistic") && !input.get("statistic").isNull()
                ? input.get("statistic").asText()
                : "Sum";
        int periodMinutes = input.has("period_minutes") && !input.get("period_minutes").isNull()
                ? input.get("period_minutes").asInt()
                : 5;
        int startMinutesAgo = input.has("start_minutes_ago") && !input.get("start_minutes_ago").isNull()
                ? input.get("start_minutes_ago").asInt()
                : 60;
        String dimensionName = input.has("dimension_name") && !input.get("dimension_name").isNull()
                ? input.get("dimension_name").asText()
                : null;
        String dimensionValue = input.has("dimension_value") && !input.get("dimension_value").isNull()
                ? input.get("dimension_value").asText()
                : null;

        MetricQuery query = MetricQuery.builder()
                .namespace(namespace)
                .metricName(metricName)
                .statistic(statistic)
                .periodMinutes(periodMinutes)
                .startMinutesAgo(startMinutesAgo)
                .dimensionName(dimensionName)
                .dimensionValue(dimensionValue)
                .build();

        log.info("Querying metrics: namespace={}, metric={}, statistic={}, lookback={}min",
                namespace, metricName, statistic, startMinutesAgo);

        MetricQueryResult result = client.queryMetrics(context, query);

        StringBuilder sb = new StringBuilder();
        sb.append("## CloudWatch Metrics — ").append(namespace).append("/").append(metricName).append("\n");
        sb.append("Last ").append(startMinutesAgo).append(" min | Period: ").append(periodMinutes).append(" min\n");
        if (dimensionName != null && dimensionValue != null) {
            sb.append("Dimension: ").append(dimensionName).append("=").append(dimensionValue).append("\n");
        }
        sb.append("\n");

        sb.append("| Statistic | Value |\n");
        sb.append("|-----------|-------|\n");

        if (result.getAverage() != null) {
            sb.append("| Average | ").append(formatValue(result.getAverage(), result.getUnit())).append(" |\n");
        }
        if (result.getSum() != null) {
            sb.append("| Sum | ").append(formatValue(result.getSum(), result.getUnit())).append(" |\n");
        }
        if (result.getMinimum() != null) {
            sb.append("| Minimum | ").append(formatValue(result.getMinimum(), result.getUnit())).append(" |\n");
        }
        if (result.getMaximum() != null) {
            sb.append("| Maximum | ").append(formatValue(result.getMaximum(), result.getUnit())).append(" |\n");
        }
        if (result.getSampleCount() != null) {
            sb.append("| Sample Count | ").append(result.getSampleCount()).append(" |\n");
        }

        if (result.getSampleCount() == null || result.getSampleCount() == 0) {
            sb.append("\n*No data points found for this metric in the specified time window.*");
        }

        return ToolResult.success(id, sb.toString());
    }

    private String formatValue(Double value, String unit) {
        if (value == null) return "N/A";
        if ("Milliseconds".equals(unit)) {
            return String.format("%.2f ms", value);
        } else if ("Percent".equals(unit)) {
            return String.format("%.2f%%", value);
        } else if ("Bytes".equals(unit)) {
            if (value > 1_000_000) {
                return String.format("%.2f MB", value / 1_000_000);
            } else if (value > 1_000) {
                return String.format("%.2f KB", value / 1_000);
            }
            return String.format("%.0f B", value);
        } else {
            return String.format("%.2f", value);
        }
    }
}
