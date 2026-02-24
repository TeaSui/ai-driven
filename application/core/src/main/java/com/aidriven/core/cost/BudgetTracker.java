package com.aidriven.core.cost;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;

/**
 * Tracks monthly AI spend, publishes to CloudWatch, and provides circuit-break
 * logic when the configured monthly budget is exceeded.
 *
 * <p>
 * Design notes:
 * <ul>
 * <li>The CloudWatch custom metric {@code AIDriven/MonthlyCostUSD} is the
 * real-time guard (allows fast Lambda-side checks).</li>
 * <li>AWS Budgets / Cost Explorer remain the authoritative billing sources
 * and should be configured separately at the infra layer.</li>
 * <li>If the CloudWatch client is null or unavailable, recording is a
 * no-op — the circuit-break still works via the provided totalMonthlyCost
 * parameter.</li>
 * </ul>
 */
@Slf4j
public class BudgetTracker {

    static final String NAMESPACE = "AIDriven";
    static final String METRIC_NAME = "MonthlyCostUSD";

    private final CloudWatchClient cloudWatchClient;
    private final double monthlyBudgetUsd;

    public BudgetTracker(CloudWatchClient cloudWatchClient, double monthlyBudgetUsd) {
        this.cloudWatchClient = cloudWatchClient;
        this.monthlyBudgetUsd = monthlyBudgetUsd;
    }

    /**
     * Publishes a single-invocation cost point to CloudWatch.
     * Errors are swallowed so they don't block the main agent flow.
     *
     * @param ticketKey The Jira ticket that consumed tokens
     * @param costUsd   The cost for this single invocation
     */
    public void recordUsage(String ticketKey, double costUsd) {
        if (cloudWatchClient == null) {
            log.debug("CloudWatch client not configured — skipping metric publish for {}", ticketKey);
            return;
        }
        try {
            MetricDatum datum = MetricDatum.builder()
                    .metricName(METRIC_NAME)
                    .value(costUsd)
                    .unit(StandardUnit.NONE)
                    .timestamp(Instant.now())
                    .dimensions(
                            Dimension.builder().name("TicketKey").value(ticketKey).build())
                    .build();

            cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                    .namespace(NAMESPACE)
                    .metricData(datum)
                    .build());

            log.debug("Published ${} cost metric for ticket {}", costUsd, ticketKey);
        } catch (Exception e) {
            log.warn("Failed to publish cost metric for ticket {}: {}", ticketKey, e.getMessage());
        }
    }

    /**
     * Returns true if the given total monthly spend has met or exceeded the
     * configured budget.
     *
     * @param totalMonthlyCostUsd Accumulated monthly cost from DynamoDB or invoker
     * @return true if the budget is exhausted
     */
    public boolean isBudgetExceeded(double totalMonthlyCostUsd) {
        return totalMonthlyCostUsd >= monthlyBudgetUsd;
    }
}
