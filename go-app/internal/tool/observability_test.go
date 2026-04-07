package tool

import (
	"context"
	"errors"
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- mock observability client ---

type mockObservabilityClient struct {
	queryLogsFn    func(ctx context.Context, op *spi.OperationContext, query *spi.LogQuery) (*spi.LogQueryResult, error)
	queryMetricsFn func(ctx context.Context, op *spi.OperationContext, query *spi.MetricQuery) (*spi.MetricQueryResult, error)
}

func (m *mockObservabilityClient) QueryLogs(ctx context.Context, op *spi.OperationContext, query *spi.LogQuery) (*spi.LogQueryResult, error) {
	if m.queryLogsFn != nil {
		return m.queryLogsFn(ctx, op, query)
	}
	return &spi.LogQueryResult{
		Lines:      []string{"2026-01-01 INFO Starting app", "2026-01-01 ERROR Something failed"},
		Truncated:  false,
		TotalLines: 2,
	}, nil
}

func (m *mockObservabilityClient) QueryMetrics(ctx context.Context, op *spi.OperationContext, query *spi.MetricQuery) (*spi.MetricQueryResult, error) {
	if m.queryMetricsFn != nil {
		return m.queryMetricsFn(ctx, op, query)
	}
	sum := 42.0
	avg := 21.0
	return &spi.MetricQueryResult{
		Namespace:     query.Namespace,
		MetricName:    query.MetricName,
		Unit:          "Count",
		Sum:           &sum,
		Average:       &avg,
		Statistic:     query.Statistic,
		PeriodMinutes: query.PeriodMinutes,
		StartTime:     "2026-01-01T00:00:00Z",
		EndTime:       "2026-01-01T01:00:00Z",
	}, nil
}

func newTestObsProvider() (*ObservabilityToolProvider, *mockObservabilityClient) {
	mock := &mockObservabilityClient{}
	return NewObservabilityToolProvider(mock), mock
}

func TestObservabilityToolProvider_Namespace(t *testing.T) {
	p, _ := newTestObsProvider()
	assert.Equal(t, "observability", p.Namespace())
}

func TestObservabilityToolProvider_Definitions(t *testing.T) {
	p, _ := newTestObsProvider()
	defs := p.Definitions()
	assert.Len(t, defs, 2)

	names := make(map[string]bool)
	for _, d := range defs {
		names[d.Name] = true
		assert.NotEmpty(t, d.Description)
	}
	assert.True(t, names["observability_query_logs"])
	assert.True(t, names["observability_query_metrics"])
}

func TestObservabilityToolProvider_QueryLogs(t *testing.T) {
	p, _ := newTestObsProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "observability_query_logs",
		Input: map[string]any{"log_group_pattern": "/aws/lambda/my-func"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "Log Results")
	assert.Contains(t, out.Content, "Starting app")
	assert.Contains(t, out.Content, "Something failed")
}

func TestObservabilityToolProvider_QueryLogs_WithOptionals(t *testing.T) {
	p, mock := newTestObsProvider()
	var capturedQuery *spi.LogQuery
	mock.queryLogsFn = func(_ context.Context, _ *spi.OperationContext, q *spi.LogQuery) (*spi.LogQueryResult, error) {
		capturedQuery = q
		return &spi.LogQueryResult{Lines: []string{"line1"}, TotalLines: 1}, nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:   "t1",
		Name: "observability_query_logs",
		Input: map[string]any{
			"log_group_pattern": "/aws/lambda/test",
			"filter_pattern":    "ERROR",
			"start_minutes_ago": float64(120),
			"limit":             float64(50),
		},
	})
	assert.False(t, out.IsError)
	require.NotNil(t, capturedQuery)
	assert.Equal(t, "/aws/lambda/test", capturedQuery.LogGroupPattern)
	assert.Equal(t, "ERROR", capturedQuery.FilterPattern)
	assert.Equal(t, 120, capturedQuery.StartMinutesAgo)
	assert.Equal(t, 50, capturedQuery.Limit)
}

func TestObservabilityToolProvider_QueryLogs_Error(t *testing.T) {
	p, mock := newTestObsProvider()
	mock.queryLogsFn = func(context.Context, *spi.OperationContext, *spi.LogQuery) (*spi.LogQueryResult, error) {
		return nil, errors.New("access denied")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "observability_query_logs",
		Input: map[string]any{"log_group_pattern": "/aws/lambda/test"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "access denied")
}

func TestObservabilityToolProvider_QueryLogs_MissingPattern(t *testing.T) {
	p, _ := newTestObsProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "observability_query_logs",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "log_group_pattern is required")
}

func TestObservabilityToolProvider_QueryLogs_EmptyResult(t *testing.T) {
	p, mock := newTestObsProvider()
	mock.queryLogsFn = func(context.Context, *spi.OperationContext, *spi.LogQuery) (*spi.LogQueryResult, error) {
		return &spi.LogQueryResult{Lines: nil, TotalLines: 0}, nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "observability_query_logs",
		Input: map[string]any{"log_group_pattern": "/aws/lambda/test"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "No log entries found")
}

func TestObservabilityToolProvider_QueryMetrics(t *testing.T) {
	p, _ := newTestObsProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:   "t1",
		Name: "observability_query_metrics",
		Input: map[string]any{
			"namespace":   "AWS/Lambda",
			"metric_name": "Invocations",
		},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "AWS/Lambda")
	assert.Contains(t, out.Content, "Invocations")
	assert.Contains(t, out.Content, "42.0000")
	assert.Contains(t, out.Content, "21.0000")
}

func TestObservabilityToolProvider_QueryMetrics_WithDimensions(t *testing.T) {
	p, mock := newTestObsProvider()
	var capturedQuery *spi.MetricQuery
	mock.queryMetricsFn = func(_ context.Context, _ *spi.OperationContext, q *spi.MetricQuery) (*spi.MetricQueryResult, error) {
		capturedQuery = q
		return &spi.MetricQueryResult{
			Namespace:  q.Namespace,
			MetricName: q.MetricName,
			Unit:       "Milliseconds",
			Statistic:  q.Statistic,
		}, nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:   "t1",
		Name: "observability_query_metrics",
		Input: map[string]any{
			"namespace":       "AWS/Lambda",
			"metric_name":     "Duration",
			"statistic":       "Average",
			"period_minutes":  float64(10),
			"dimension_name":  "FunctionName",
			"dimension_value": "my-func",
		},
	})
	assert.False(t, out.IsError)
	require.NotNil(t, capturedQuery)
	assert.Equal(t, "Average", capturedQuery.Statistic)
	assert.Equal(t, 10, capturedQuery.PeriodMinutes)
	assert.Equal(t, "FunctionName", capturedQuery.DimensionName)
	assert.Equal(t, "my-func", capturedQuery.DimensionValue)
}

func TestObservabilityToolProvider_QueryMetrics_Error(t *testing.T) {
	p, mock := newTestObsProvider()
	mock.queryMetricsFn = func(context.Context, *spi.OperationContext, *spi.MetricQuery) (*spi.MetricQueryResult, error) {
		return nil, errors.New("throttled")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:   "t1",
		Name: "observability_query_metrics",
		Input: map[string]any{
			"namespace":   "AWS/Lambda",
			"metric_name": "Errors",
		},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "throttled")
}

func TestObservabilityToolProvider_QueryMetrics_MissingParams(t *testing.T) {
	p, _ := newTestObsProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "observability_query_metrics",
		Input: map[string]any{"namespace": "AWS/Lambda"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "metric_name are required")
}

func TestObservabilityToolProvider_UnknownTool(t *testing.T) {
	p, _ := newTestObsProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "observability_nonexistent",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "unknown tool")
}

func TestObservabilityToolProvider_ImplementsProvider(t *testing.T) {
	p, _ := newTestObsProvider()
	var _ Provider = p
	require.NotNil(t, p)
}

func TestFormatLogResult_Truncated(t *testing.T) {
	result := &spi.LogQueryResult{
		Lines:      []string{"line1", "line2"},
		Truncated:  true,
		TotalLines: 100,
	}
	formatted := formatLogResult(result)
	assert.Contains(t, formatted, "truncated")
	assert.Contains(t, formatted, "100 lines")
}

func TestFormatLogResult_PipeEscape(t *testing.T) {
	result := &spi.LogQueryResult{
		Lines:      []string{"key|value"},
		TotalLines: 1,
	}
	formatted := formatLogResult(result)
	assert.Contains(t, formatted, "key\\|value")
}

func TestFormatMetricResult_Nil(t *testing.T) {
	assert.Contains(t, formatMetricResult(nil), "No metric data found")
}
