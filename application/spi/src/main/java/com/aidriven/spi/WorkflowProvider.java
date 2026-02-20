package com.aidriven.spi;

import java.util.Map;

/**
 * Service Provider Interface for workflow orchestration.
 * Implementations can wrap AWS Step Functions, Temporal, Camunda, etc.
 */
public interface WorkflowProvider {

    /**
     * Unique identifier for this provider.
     */
    String providerId();

    /**
     * Starts a workflow execution.
     *
     * @param workflowId    Identifier of the workflow definition
     * @param executionName Unique name for this execution
     * @param input         Input data for the workflow
     * @return Execution identifier
     */
    String startExecution(String workflowId, String executionName, Map<String, Object> input) throws Exception;

    /**
     * Sends a task completion signal (for callback-based steps).
     */
    void sendTaskSuccess(String taskToken, Map<String, Object> output) throws Exception;

    /**
     * Sends a task failure signal.
     */
    void sendTaskFailure(String taskToken, String error, String cause) throws Exception;
}
