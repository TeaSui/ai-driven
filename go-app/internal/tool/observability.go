package tool

import (
	"context"
	"fmt"
	"strings"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

const observabilityNamespace = "observability"

// ObservabilityToolProvider exposes log and metric querying as AI tools.
type ObservabilityToolProvider struct {
	client spi.ObservabilityClient
}

// NewObservabilityToolProvider creates a new ObservabilityToolProvider.
func NewObservabilityToolProvider(client spi.ObservabilityClient) *ObservabilityToolProvider {
	return &ObservabilityToolProvider{client: client}
}

func (p *ObservabilityToolProvider) Namespace() string { return observabilityNamespace }

func (p *ObservabilityToolProvider) MaxOutputChars() int { return 80_000 }

func (p *ObservabilityToolProvider) Definitions() []Def {
	return []Def{
		{
			Name:        "observability_query_logs",
			Description: "Query application logs from CloudWatch Logs.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"log_group_pattern": map[string]any{"type": "string", "description": "Pattern to match log group names."},
					"filter_pattern":    map[string]any{"type": "string", "description": "CloudWatch Logs filter pattern."},
					"start_minutes_ago": map[string]any{"type": "integer", "description": "How many minutes ago to start the query. Default: 60."},
					"limit":             map[string]any{"type": "integer", "description": "Maximum number of log lines to return. Default: 100."},
				},
				"required": []string{"log_group_pattern"},
			},
		},
		{
			Name:        "observability_query_metrics",
			Description: "Query CloudWatch metrics for a given namespace and metric.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"namespace":         map[string]any{"type": "string", "description": "CloudWatch metric namespace."},
					"metric_name":       map[string]any{"type": "string", "description": "Name of the metric."},
					"statistic":         map[string]any{"type": "string", "description": "Statistic to retrieve (Sum, Average, Minimum, Maximum, SampleCount). Default: Sum."},
					"period_minutes":    map[string]any{"type": "integer", "description": "Period in minutes for each data point. Default: 5."},
					"start_minutes_ago": map[string]any{"type": "integer", "description": "How many minutes ago to start the query. Default: 60."},
					"dimension_name":    map[string]any{"type": "string", "description": "Dimension name to filter by."},
					"dimension_value":   map[string]any{"type": "string", "description": "Dimension value to filter by."},
				},
				"required": []string{"namespace", "metric_name"},
			},
		},
	}
}

func (p *ObservabilityToolProvider) Execute(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	suffix := strings.TrimPrefix(call.Name, observabilityNamespace+"_")
	switch suffix {
	case "query_logs":
		return p.queryLogs(ctx, op, call)
	case "query_metrics":
		return p.queryMetrics(ctx, op, call)
	default:
		return ErrorOutput(call.ID, fmt.Sprintf("unknown tool: %s", call.Name))
	}
}

func (p *ObservabilityToolProvider) queryLogs(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	logGroupPattern := call.String("log_group_pattern")
	if logGroupPattern == "" {
		return ErrorOutput(call.ID, "log_group_pattern is required")
	}

	query := spi.DefaultLogQuery()
	query.LogGroupPattern = logGroupPattern
	if fp := call.String("filter_pattern"); fp != "" {
		query.FilterPattern = fp
	}
	if sma := call.Int("start_minutes_ago", 0); sma > 0 {
		query.StartMinutesAgo = sma
	}
	if lim := call.Int("limit", 0); lim > 0 {
		query.Limit = lim
	}

	result, err := p.client.QueryLogs(ctx, op, &query)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("log query failed: %v", err))
	}

	return SuccessOutput(call.ID, formatLogResult(result))
}

func formatLogResult(r *spi.LogQueryResult) string {
	if r == nil || len(r.Lines) == 0 {
		return "No log entries found."
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("**Log Results** (%d lines", r.TotalLines))
	if r.Truncated {
		sb.WriteString(", truncated")
	}
	sb.WriteString(")\n\n")
	sb.WriteString("| # | Log Line |\n")
	sb.WriteString("|---|----------|\n")
	for i, line := range r.Lines {
		// Escape pipes in the log line for markdown table safety.
		escaped := strings.ReplaceAll(line, "|", "\\|")
		sb.WriteString(fmt.Sprintf("| %d | %s |\n", i+1, escaped))
	}
	return sb.String()
}

func (p *ObservabilityToolProvider) queryMetrics(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	ns := call.String("namespace")
	metricName := call.String("metric_name")
	if ns == "" || metricName == "" {
		return ErrorOutput(call.ID, "namespace and metric_name are required")
	}

	query := spi.DefaultMetricQuery()
	query.Namespace = ns
	query.MetricName = metricName
	if stat := call.String("statistic"); stat != "" {
		query.Statistic = stat
	}
	if pm := call.Int("period_minutes", 0); pm > 0 {
		query.PeriodMinutes = pm
	}
	if sma := call.Int("start_minutes_ago", 0); sma > 0 {
		query.StartMinutesAgo = sma
	}
	if dn := call.String("dimension_name"); dn != "" {
		query.DimensionName = dn
	}
	if dv := call.String("dimension_value"); dv != "" {
		query.DimensionValue = dv
	}

	result, err := p.client.QueryMetrics(ctx, op, &query)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("metric query failed: %v", err))
	}

	return SuccessOutput(call.ID, formatMetricResult(result))
}

func formatMetricResult(r *spi.MetricQueryResult) string {
	if r == nil {
		return "No metric data found."
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("**Metric: %s / %s**\n\n", r.Namespace, r.MetricName))
	sb.WriteString("| Property | Value |\n")
	sb.WriteString("|----------|-------|\n")
	sb.WriteString(fmt.Sprintf("| Unit | %s |\n", r.Unit))
	sb.WriteString(fmt.Sprintf("| Statistic | %s |\n", r.Statistic))
	sb.WriteString(fmt.Sprintf("| Period | %d min |\n", r.PeriodMinutes))
	sb.WriteString(fmt.Sprintf("| Time Range | %s — %s |\n", r.StartTime, r.EndTime))
	if r.Sum != nil {
		sb.WriteString(fmt.Sprintf("| Sum | %.4f |\n", *r.Sum))
	}
	if r.Average != nil {
		sb.WriteString(fmt.Sprintf("| Average | %.4f |\n", *r.Average))
	}
	if r.Minimum != nil {
		sb.WriteString(fmt.Sprintf("| Minimum | %.4f |\n", *r.Minimum))
	}
	if r.Maximum != nil {
		sb.WriteString(fmt.Sprintf("| Maximum | %.4f |\n", *r.Maximum))
	}
	if r.SampleCount != nil {
		sb.WriteString(fmt.Sprintf("| Sample Count | %d |\n", *r.SampleCount))
	}
	return sb.String()
}
