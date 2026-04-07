package observability

import (
	"context"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatch"
	cwtypes "github.com/aws/aws-sdk-go-v2/service/cloudwatch/types"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatchlogs"
	logstypes "github.com/aws/aws-sdk-go-v2/service/cloudwatchlogs/types"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

const (
	maxLogChars     = 8000
	maxPollAttempts = 20
	pollInterval    = 500 * time.Millisecond
)

// CloudWatchLogsAPI is the subset of cloudwatchlogs.Client used by CloudWatchObservabilityClient.
type CloudWatchLogsAPI interface {
	StartQuery(ctx context.Context, params *cloudwatchlogs.StartQueryInput, optFns ...func(*cloudwatchlogs.Options)) (*cloudwatchlogs.StartQueryOutput, error)
	GetQueryResults(ctx context.Context, params *cloudwatchlogs.GetQueryResultsInput, optFns ...func(*cloudwatchlogs.Options)) (*cloudwatchlogs.GetQueryResultsOutput, error)
}

// CloudWatchMetricsAPI is the subset of cloudwatch.Client used by CloudWatchObservabilityClient.
type CloudWatchMetricsAPI interface {
	GetMetricStatistics(ctx context.Context, params *cloudwatch.GetMetricStatisticsInput, optFns ...func(*cloudwatch.Options)) (*cloudwatch.GetMetricStatisticsOutput, error)
}

// CloudWatchObservabilityClient implements spi.ObservabilityClient using AWS CloudWatch.
type CloudWatchObservabilityClient struct {
	logsClient    CloudWatchLogsAPI
	metricsClient CloudWatchMetricsAPI
}

// NewCloudWatchObservabilityClient creates a new CloudWatch-backed observability client.
func NewCloudWatchObservabilityClient(logsClient CloudWatchLogsAPI, metricsClient CloudWatchMetricsAPI) *CloudWatchObservabilityClient {
	return &CloudWatchObservabilityClient{
		logsClient:    logsClient,
		metricsClient: metricsClient,
	}
}

// QueryLogs starts a CloudWatch Insights query and polls for results.
func (c *CloudWatchObservabilityClient) QueryLogs(ctx context.Context, op *spi.OperationContext, query *spi.LogQuery) (*spi.LogQueryResult, error) {
	now := time.Now()
	startTime := now.Add(-time.Duration(query.StartMinutesAgo) * time.Minute)
	endTime := now.Add(-time.Duration(query.EndMinutesAgo) * time.Minute)

	var limit int32
	switch {
	case query.Limit <= 0:
		limit = 100
	case query.Limit > math.MaxInt32:
		limit = math.MaxInt32
	default:
		limit = int32(query.Limit) //nolint:gosec // bounds checked above
	}

	logGroupPattern := query.LogGroupPattern
	if logGroupPattern == "" {
		return nil, fmt.Errorf("logGroupPattern is required")
	}

	filterPattern := query.FilterPattern
	if filterPattern == "" {
		filterPattern = "fields @timestamp, @message | sort @timestamp desc"
	} else {
		filterPattern = fmt.Sprintf("fields @timestamp, @message | filter @message like /%s/ | sort @timestamp desc", filterPattern)
	}

	startQueryOut, err := c.logsClient.StartQuery(ctx, &cloudwatchlogs.StartQueryInput{
		LogGroupName: aws.String(logGroupPattern),
		StartTime:    aws.Int64(startTime.Unix()),
		EndTime:      aws.Int64(endTime.Unix()),
		QueryString:  aws.String(filterPattern),
		Limit:        aws.Int32(limit),
	})
	if err != nil {
		return nil, fmt.Errorf("failed to start CloudWatch query: %w", err)
	}

	queryID := aws.ToString(startQueryOut.QueryId)
	log.Debug().Str("queryId", queryID).Msg("started CloudWatch Insights query")

	// Poll for results
	for attempt := 0; attempt < maxPollAttempts; attempt++ {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(pollInterval):
		}

		resultsOut, err := c.logsClient.GetQueryResults(ctx, &cloudwatchlogs.GetQueryResultsInput{
			QueryId: aws.String(queryID),
		})
		if err != nil {
			return nil, fmt.Errorf("failed to get query results: %w", err)
		}

		if resultsOut.Status == logstypes.QueryStatusComplete || resultsOut.Status == logstypes.QueryStatusFailed {
			return formatLogResults(resultsOut), nil
		}
	}

	return nil, fmt.Errorf("query %s timed out after %d poll attempts", queryID, maxPollAttempts)
}

func formatLogResults(out *cloudwatchlogs.GetQueryResultsOutput) *spi.LogQueryResult {
	lines := make([]string, 0, len(out.Results))
	totalChars := 0

	for _, row := range out.Results {
		var msg string
		for _, field := range row {
			if aws.ToString(field.Field) == "@message" {
				msg = aws.ToString(field.Value)
				break
			}
		}
		if msg == "" {
			continue
		}
		if totalChars+len(msg) > maxLogChars {
			return &spi.LogQueryResult{
				Lines:      lines,
				Truncated:  true,
				TotalLines: len(out.Results),
			}
		}
		lines = append(lines, msg)
		totalChars += len(msg)
	}

	return &spi.LogQueryResult{
		Lines:      lines,
		Truncated:  false,
		TotalLines: len(lines),
	}
}

// QueryMetrics queries CloudWatch metric statistics and aggregates the datapoints.
func (c *CloudWatchObservabilityClient) QueryMetrics(ctx context.Context, op *spi.OperationContext, query *spi.MetricQuery) (*spi.MetricQueryResult, error) {
	now := time.Now()
	startTime := now.Add(-time.Duration(query.StartMinutesAgo) * time.Minute)
	endTime := now.Add(-time.Duration(query.EndMinutesAgo) * time.Minute)

	var period int32
	periodSeconds := query.PeriodMinutes * 60
	switch {
	case periodSeconds <= 0:
		period = 300
	case periodSeconds > math.MaxInt32:
		period = math.MaxInt32
	default:
		period = int32(periodSeconds) //nolint:gosec // bounds checked above
	}

	input := &cloudwatch.GetMetricStatisticsInput{
		Namespace:  aws.String(query.Namespace),
		MetricName: aws.String(query.MetricName),
		StartTime:  aws.Time(startTime),
		EndTime:    aws.Time(endTime),
		Period:     aws.Int32(period),
		Statistics: []cwtypes.Statistic{mapStatistic(query.Statistic)},
	}

	if query.DimensionName != "" && query.DimensionValue != "" {
		input.Dimensions = []cwtypes.Dimension{
			{
				Name:  aws.String(query.DimensionName),
				Value: aws.String(query.DimensionValue),
			},
		}
	}

	out, err := c.metricsClient.GetMetricStatistics(ctx, input)
	if err != nil {
		return nil, fmt.Errorf("failed to get metric statistics: %w", err)
	}

	result := &spi.MetricQueryResult{
		Namespace:     query.Namespace,
		MetricName:    query.MetricName,
		Statistic:     query.Statistic,
		PeriodMinutes: query.PeriodMinutes,
		StartTime:     startTime.UTC().Format(time.RFC3339),
		EndTime:       endTime.UTC().Format(time.RFC3339),
	}

	aggregateDatapoints(out.Datapoints, result)
	return result, nil
}

func aggregateDatapoints(datapoints []cwtypes.Datapoint, result *spi.MetricQueryResult) {
	if len(datapoints) == 0 {
		return
	}

	var sumTotal, minVal, maxVal, avgTotal float64
	var sampleTotal int
	minVal = *datapoints[0].Minimum
	maxVal = *datapoints[0].Maximum

	for i, dp := range datapoints {
		if dp.Unit != "" {
			result.Unit = string(dp.Unit)
		}
		if dp.Sum != nil {
			sumTotal += *dp.Sum
		}
		if dp.SampleCount != nil {
			sampleTotal += int(*dp.SampleCount)
		}
		if dp.Average != nil {
			avgTotal += *dp.Average
		}
		if dp.Minimum != nil {
			if i == 0 || *dp.Minimum < minVal {
				minVal = *dp.Minimum
			}
		}
		if dp.Maximum != nil {
			if i == 0 || *dp.Maximum > maxVal {
				maxVal = *dp.Maximum
			}
		}
	}

	n := len(datapoints)
	avg := avgTotal / float64(n)
	result.Average = &avg
	result.Sum = &sumTotal
	result.Minimum = &minVal
	result.Maximum = &maxVal
	result.SampleCount = &sampleTotal
}

func mapStatistic(stat string) cwtypes.Statistic {
	switch strings.ToLower(stat) {
	case "average":
		return cwtypes.StatisticAverage
	case "sum":
		return cwtypes.StatisticSum
	case "minimum":
		return cwtypes.StatisticMinimum
	case "maximum":
		return cwtypes.StatisticMaximum
	case "samplecount":
		return cwtypes.StatisticSampleCount
	default:
		return cwtypes.StatisticSum
	}
}
