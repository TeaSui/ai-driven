package com.teasui.crm.common.event.workflow;

import com.teasui.crm.common.event.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * Event published when a workflow execution changes state.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class WorkflowExecutionEvent extends BaseEvent {

    public static final String EVENT_TYPE = "WORKFLOW_EXECUTION";

    private String workflowId;
    private String executionId;
    private String workflowName;
    private ExecutionStatus status;
    private String currentStepId;
    private String currentStepName;
    private Map<String, Object> context;
    private String errorMessage;
    private String triggeredBy;

    public enum ExecutionStatus {
        STARTED,
        STEP_COMPLETED,
        STEP_FAILED,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
