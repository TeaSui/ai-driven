package com.aidriven.core.tenant;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Configuration for a single workflow step.
 * Allows tenants to customize the workflow pipeline with
 * tenant-specific steps, conditions, and parameters.
 */
@Data
@Builder
public class WorkflowStepConfig {

    /** Step identifier (e.g., "fetch_ticket", "generate_code", "create_pr"). */
    private String stepId;

    /** Human-readable step name. */
    private String stepName;

    /** Whether this step is enabled for the tenant. */
    @Builder.Default
    private boolean enabled = true;

    /** Step-specific parameters. */
    private Map<String, Object> parameters;

    /** Condition expression to evaluate before executing this step. */
    private String condition;

    /** Timeout in seconds for this step. */
    @Builder.Default
    private int timeoutSeconds = 300;

    /** Whether to retry on failure. */
    @Builder.Default
    private boolean retryOnFailure = false;

    /** Maximum retry attempts. */
    @Builder.Default
    private int maxRetries = 3;
}
