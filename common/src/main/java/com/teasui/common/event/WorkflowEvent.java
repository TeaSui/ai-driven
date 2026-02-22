package com.teasui.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class WorkflowEvent extends BaseEvent {

    public enum Type {
        WORKFLOW_CREATED,
        WORKFLOW_STARTED,
        WORKFLOW_COMPLETED,
        WORKFLOW_FAILED,
        WORKFLOW_PAUSED,
        WORKFLOW_RESUMED,
        STEP_COMPLETED,
        STEP_FAILED
    }

    private Type type;
    private String workflowId;
    private String workflowName;
    private String stepId;
    private String stepName;
    private Map<String, Object> payload;
    private String errorMessage;

    public static WorkflowEvent workflowStarted(String tenantId, String workflowId, String workflowName) {
        return WorkflowEvent.builder()
                .eventId(generateEventId())
                .eventType("WORKFLOW")
                .tenantId(tenantId)
                .occurredAt(Instant.now())
                .version(1)
                .type(Type.WORKFLOW_STARTED)
                .workflowId(workflowId)
                .workflowName(workflowName)
                .build();
    }

    public static WorkflowEvent workflowCompleted(String tenantId, String workflowId, String workflowName) {
        return WorkflowEvent.builder()
                .eventId(generateEventId())
                .eventType("WORKFLOW")
                .tenantId(tenantId)
                .occurredAt(Instant.now())
                .version(1)
                .type(Type.WORKFLOW_COMPLETED)
                .workflowId(workflowId)
                .workflowName(workflowName)
                .build();
    }

    public static WorkflowEvent workflowFailed(String tenantId, String workflowId, String errorMessage) {
        return WorkflowEvent.builder()
                .eventId(generateEventId())
                .eventType("WORKFLOW")
                .tenantId(tenantId)
                .occurredAt(Instant.now())
                .version(1)
                .type(Type.WORKFLOW_FAILED)
                .workflowId(workflowId)
                .errorMessage(errorMessage)
                .build();
    }
}
