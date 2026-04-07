package spi

import "context"

// ObservabilityClient is the SPI for querying live log and metric data.
type ObservabilityClient interface {
	QueryLogs(ctx context.Context, op *OperationContext, query *LogQuery) (*LogQueryResult, error)
	QueryMetrics(ctx context.Context, op *OperationContext, query *MetricQuery) (*MetricQueryResult, error)
}

// LogQuery defines parameters for a log search.
type LogQuery struct {
	LogGroupPattern string `json:"logGroupPattern"`
	FilterPattern   string `json:"filterPattern"`
	StartMinutesAgo int    `json:"startMinutesAgo"`
	EndMinutesAgo   int    `json:"endMinutesAgo"`
	Limit           int    `json:"limit"`
}

func DefaultLogQuery() LogQuery {
	return LogQuery{
		StartMinutesAgo: 60,
		EndMinutesAgo:   0,
		Limit:           100,
	}
}

// LogQueryResult holds the result of a log search.
type LogQueryResult struct {
	Lines      []string `json:"lines"`
	Truncated  bool     `json:"truncated"`
	TotalLines int      `json:"totalLines"`
}

// MetricQuery defines parameters for a metric query.
type MetricQuery struct {
	Namespace       string `json:"namespace"`
	MetricName      string `json:"metricName"`
	Statistic       string `json:"statistic"`
	PeriodMinutes   int    `json:"periodMinutes"`
	StartMinutesAgo int    `json:"startMinutesAgo"`
	EndMinutesAgo   int    `json:"endMinutesAgo"`
	DimensionName   string `json:"dimensionName,omitempty"`
	DimensionValue  string `json:"dimensionValue,omitempty"`
}

func DefaultMetricQuery() MetricQuery {
	return MetricQuery{
		Statistic:       "Sum",
		PeriodMinutes:   5,
		StartMinutesAgo: 60,
		EndMinutesAgo:   0,
	}
}

// MetricQueryResult holds the result of a metric query.
type MetricQueryResult struct {
	Namespace     string   `json:"namespace"`
	MetricName    string   `json:"metricName"`
	Unit          string   `json:"unit"`
	Average       *float64 `json:"average,omitempty"`
	Sum           *float64 `json:"sum,omitempty"`
	Minimum       *float64 `json:"minimum,omitempty"`
	Maximum       *float64 `json:"maximum,omitempty"`
	SampleCount   *int     `json:"sampleCount,omitempty"`
	Statistic     string   `json:"statistic"`
	PeriodMinutes int      `json:"periodMinutes"`
	StartTime     string   `json:"startTime"`
	EndTime       string   `json:"endTime"`
}
