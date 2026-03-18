package com.aidriven.core.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for logging CloudWatch Embedded Metric Format (EMF) logs.
 * This class builds and prints JSON objects that CloudWatch automatically
 * extracts
 * into metrics, without needing explicit PutMetricData API calls.
 */
@Slf4j
public class AgentMetrics {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NAMESPACE = "AiAgent";

    private final Map<String, Object> properties = new HashMap<>();
    private final List<Map<String, String>> metricDefinitions = new ArrayList<>();
    private final List<List<String>> dimensions = new ArrayList<>();

    public AgentMetrics() {
        this.dimensions.add(List.of("TenantId", "Platform"));
    }

    public AgentMetrics withTenantId(String tenantId) {
        if (tenantId != null) {
            this.properties.put("TenantId", tenantId);
        }
        return this;
    }

    public AgentMetrics withPlatform(String platform) {
        if (platform != null) {
            this.properties.put("Platform", platform);
        }
        return this;
    }

    public AgentMetrics putProperty(String key, Object value) {
        if (value != null) {
            this.properties.put(key, value);
        }
        return this;
    }

    public AgentMetrics putMetric(String name, double value, String unit) {
        this.properties.put(name, value);
        this.metricDefinitions.add(Map.of("Name", name, "Unit", unit));
        return this;
    }

    /**
     * Records the total number of conversation turns.
     */
    public AgentMetrics recordTurns(int count) {
        return putMetric("agent.turns", count, "Count");
    }

    /**
     * Records the total context tokens used in this run.
     */
    public AgentMetrics recordTokens(int count) {
        return putMetric("agent.tokens.total", count, "Count");
    }

    /**
     * Records the number of tools used.
     */
    public AgentMetrics recordTools(int count) {
        return putMetric("agent.tools.count", count, "Count");
    }

    /**
     * Records the total latency of the run in ms.
     */
    public AgentMetrics recordLatency(long ms) {
        return putMetric("agent.latency.ms", ms, "Milliseconds");
    }

    /**
     * Records any errors encountered during the run.
     */
    public AgentMetrics recordErrors(int count) {
        return putMetric("agent.errors", count, "Count");
    }

    /**
     * Flushes the metrics out to standard log output using EMF JSON mapping.
     */
    public void flush() {
        Map<String, Object> emfPayload = new HashMap<>(this.properties);

        Map<String, Object> awsObj = new HashMap<>();
        awsObj.put("Timestamp", Instant.now().toEpochMilli());

        Map<String, Object> cloudWatchMetrics = new HashMap<>();
        cloudWatchMetrics.put("Namespace", NAMESPACE);
        cloudWatchMetrics.put("Dimensions", this.dimensions);
        cloudWatchMetrics.put("Metrics", this.metricDefinitions);

        awsObj.put("CloudWatchMetrics", List.of(cloudWatchMetrics));

        emfPayload.put("_aws", awsObj);

        try {
            // EMF requires raw JSON on stdout for CloudWatch to parse natively.
            System.out.println(MAPPER.writeValueAsString(emfPayload));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize EMF metrics payload", e);
        }
    }
}
