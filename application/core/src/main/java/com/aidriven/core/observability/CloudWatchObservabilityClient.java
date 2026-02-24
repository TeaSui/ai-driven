package com.aidriven.core.observability;

import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.observability.LogQuery;
import com.aidriven.spi.observability.LogQueryResult;
import com.aidriven.spi.observability.MetricQuery;
import com.aidriven.spi.observability.MetricQueryResult;
import com.aidriven.spi.observability.ObservabilityClient;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CloudWatch implementation of {@link ObservabilityClient}.
 * Uses the Logs Insights API (StartQuery/GetQueryResults) for rich querying,
 * with a fallback to FilterLogEvents for simple pattern matching.
 * Also supports CloudWatch Metrics via GetMetricStatistics.
 */
@Slf4j
public class CloudWatchObservabilityClient implements ObservabilityClient {

    private static final int MAX_LOG_CHARS = 8_000;
    private static final int MAX_POLL_ATTEMPTS = 20;
    private static final long POLL_INTERVAL_MS = 500;

    private final CloudWatchLogsClient logsClient;
    private final CloudWatchClient metricsClient;

    public CloudWatchObservabilityClient(CloudWatchLogsClient logsClient) {
        this(logsClient, CloudWatchClient.create());
    }

    public CloudWatchObservabilityClient(CloudWatchLogsClient logsClient, CloudWatchClient metricsClient) {
        this.logsClient = logsClient;
        this.metricsClient = metricsClient;
    }

    @Override
    public LogQueryResult queryLogs(OperationContext context, LogQuery query) throws Exception {
        Instant endTime = Instant.now().minusSeconds(query.getEndMinutesAgo() * 60L);
        Instant startTime = endTime.minusSeconds(query.getStartMinutesAgo() * 60L);

        try {
            return runInsightsQuery(query, startTime, endTime);
        } catch (Exception e) {
            log.warn("CloudWatch Insights query failed, falling back to FilterLogEvents: {}", e.getMessage());
            return filterLogEvents(query, startTime, endTime);
        }
    }

    private LogQueryResult runInsightsQuery(LogQuery query, Instant startTime, Instant endTime) throws Exception {
        String queryString = buildQueryString(query);

        StartQueryRequest startRequest = StartQueryRequest.builder()
                .logGroupName(query.getLogGroupPattern())
                .startTime(startTime.toEpochMilli())
                .endTime(endTime.toEpochMilli())
                .queryString(queryString)
                .limit(query.getLimit())
                .build();

        String queryId = logsClient.startQuery(startRequest).queryId();
        log.debug("Started CloudWatch Insights query: {}", queryId);

        // Poll for results
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);

            GetQueryResultsResponse response = logsClient.getQueryResults(
                    GetQueryResultsRequest.builder().queryId(queryId).build());

            QueryStatus status = response.status();
            if (status == QueryStatus.COMPLETE) {
                return formatInsightsResults(response.results());
            }
            if (status == QueryStatus.FAILED || status == QueryStatus.CANCELLED) {
                throw new RuntimeException("Insights query ended with status: " + status);
            }
        }
        throw new RuntimeException("CloudWatch Insights query timed out after " +
                MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS + "ms");
    }

    private LogQueryResult filterLogEvents(LogQuery query, Instant startTime, Instant endTime) {
        FilterLogEventsRequest.Builder requestBuilder = FilterLogEventsRequest.builder()
                .logGroupName(query.getLogGroupPattern())
                .startTime(startTime.toEpochMilli())
                .endTime(endTime.toEpochMilli())
                .limit(query.getLimit());

        if (query.getFilterPattern() != null && !query.getFilterPattern().isBlank()) {
            requestBuilder.filterPattern(query.getFilterPattern());
        }

        FilterLogEventsResponse response = logsClient.filterLogEvents(requestBuilder.build());
        List<String> lines = response.events().stream()
                .map(e -> "[" + Instant.ofEpochMilli(e.timestamp()) + "] " + e.message().strip())
                .toList();

        return truncate(lines);
    }

    private String buildQueryString(LogQuery query) {
        if (query.getFilterPattern() != null && !query.getFilterPattern().isBlank()) {
            return "fields @timestamp, @message, @logStream | " +
                    "filter @message like /" + query.getFilterPattern() + "/ | " +
                    "sort @timestamp desc";
        }
        return "fields @timestamp, @message, @logStream | sort @timestamp desc";
    }

    private LogQueryResult formatInsightsResults(List<List<ResultField>> rows) {
        List<String> lines = new ArrayList<>();
        for (List<ResultField> row : rows) {
            StringBuilder sb = new StringBuilder();
            String ts = row.stream().filter(f -> "@timestamp".equals(f.field()))
                    .map(ResultField::value).findFirst().orElse("");
            String msg = row.stream().filter(f -> "@message".equals(f.field()))
                    .map(ResultField::value).findFirst().orElse("");
            if (!ts.isEmpty())
                sb.append("[").append(ts).append("] ");
            sb.append(msg.strip());
            lines.add(sb.toString());
        }
        return truncate(lines);
    }

    private LogQueryResult truncate(List<String> lines) {
        int total = lines.size();
        List<String> result = new ArrayList<>();
        int chars = 0;
        boolean truncated = false;

        for (String line : lines) {
            if (chars + line.length() > MAX_LOG_CHARS) {
                truncated = true;
                break;
            }
            result.add(line);
            chars += line.length() + 1; // +1 for newline
        }

        return LogQueryResult.builder()
                .lines(result)
                .truncated(truncated)
                .totalLines(total)
                .build();
    }

    @Override
    public MetricQueryResult queryMetrics(OperationContext context, MetricQuery query) throws Exception {
        Instant endTime = Instant.now().minusSeconds(query.getEndMinutesAgo() * 60L);
        Instant startTime = endTime.minusSeconds((long) query.getStartMinutesAgo() * 60L);

        GetMetricStatisticsRequest.Builder requestBuilder = GetMetricStatisticsRequest.builder()
                .namespace(query.getNamespace())
                .metricName(query.getMetricName())
                .startTime(startTime)
                .endTime(endTime)
                .period(query.getPeriodMinutes() * 60)
                .statistics(parseStatistic(query.getStatistic()));

        if (query.getDimensionName() != null && query.getDimensionValue() != null) {
            requestBuilder.dimensions(Dimension.builder()
                    .name(query.getDimensionName())
                    .value(query.getDimensionValue())
                    .build());
        }

        log.debug("Querying CloudWatch metrics: namespace={}, metric={}, statistic={}",
                query.getNamespace(), query.getMetricName(), query.getStatistic());

        GetMetricStatisticsResponse response = metricsClient.getMetricStatistics(requestBuilder.build());

        MetricQueryResult.MetricQueryResultBuilder resultBuilder = MetricQueryResult.builder()
                .namespace(query.getNamespace())
                .metricName(query.getMetricName())
                .statistic(query.getStatistic())
                .periodMinutes(query.getPeriodMinutes())
                .startTime(startTime.toString())
                .endTime(endTime.toString());

        // Aggregate datapoints
        List<Datapoint> datapoints = response.datapoints();
        if (!datapoints.isEmpty()) {
            String unit = datapoints.get(0).unitAsString();
            resultBuilder.unit(unit);

            double sumOfAverages = 0;
            double sumOfSums = 0;
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            int totalSamples = 0;

            for (Datapoint dp : datapoints) {
                if (dp.average() != null) {
                    sumOfAverages += dp.average();
                }
                if (dp.sum() != null) {
                    sumOfSums += dp.sum();
                }
                if (dp.minimum() != null && dp.minimum() < min) {
                    min = dp.minimum();
                }
                if (dp.maximum() != null && dp.maximum() > max) {
                    max = dp.maximum();
                }
                if (dp.sampleCount() != null) {
                    totalSamples += dp.sampleCount().intValue();
                }
            }

            if (!datapoints.isEmpty()) {
                resultBuilder.average(sumOfAverages / datapoints.size());
            }
            resultBuilder.sum(sumOfSums);
            if (min != Double.MAX_VALUE) {
                resultBuilder.minimum(min);
            }
            if (max != Double.MIN_VALUE) {
                resultBuilder.maximum(max);
            }
            resultBuilder.sampleCount(totalSamples);
        }

        return resultBuilder.build();
    }

    private Statistic parseStatistic(String statistic) {
        return switch (statistic.toLowerCase()) {
            case "average" -> Statistic.AVERAGE;
            case "sum" -> Statistic.SUM;
            case "minimum" -> Statistic.MINIMUM;
            case "maximum" -> Statistic.MAXIMUM;
            case "samplecount" -> Statistic.SAMPLE_COUNT;
            default -> Statistic.SUM;
        };
    }
}
