package com.aidriven.core.cost;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetTrackerTest {

    @Mock
    private CloudWatchClient cloudWatchClient;

    private BudgetTracker tracker;
    private static final double MONTHLY_BUDGET = 100.0;

    @BeforeEach
    void setUp() {
        lenient().when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(PutMetricDataResponse.builder().build());
        tracker = new BudgetTracker(cloudWatchClient, MONTHLY_BUDGET);
    }

    @Test
    void should_publish_metric_to_cloudwatch() {
        tracker.recordUsage("CRM-99", 0.50);

        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void should_not_throw_when_cloudwatch_fails() {
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch unavailable"));

        // Should log warning but not propagate
        assertDoesNotThrow(() -> tracker.recordUsage("CRM-99", 1.0));
    }

    @Test
    void should_return_false_when_budget_not_exceeded() {
        assertFalse(tracker.isBudgetExceeded(50.0));
    }

    @Test
    void should_return_true_when_budget_exactly_reached() {
        assertTrue(tracker.isBudgetExceeded(100.0));
    }

    @Test
    void should_return_true_when_budget_overrun() {
        assertTrue(tracker.isBudgetExceeded(120.0));
    }

    @Test
    void should_accept_null_cloudwatch_client_gracefully() {
        BudgetTracker noMetricsTracker = new BudgetTracker(null, 100.0);
        assertDoesNotThrow(() -> noMetricsTracker.recordUsage("CRM-01", 1.0));
        assertFalse(noMetricsTracker.isBudgetExceeded(50.0));
        assertTrue(noMetricsTracker.isBudgetExceeded(150.0));
    }
}
