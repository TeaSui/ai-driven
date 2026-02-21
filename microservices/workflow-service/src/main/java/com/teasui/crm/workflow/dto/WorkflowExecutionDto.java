package com.teasui.crm.workflow.dto;

import com.teasui.crm.workflow.domain.WorkflowExecution;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowExecutionDto {

    private String id;
    private String workflowId;
    private String tenantId;
    private WorkflowExecution.ExecutionStatus status;
    private String triggeredBy;
    private String currentStepId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    public static WorkflowExecutionDto from(WorkflowExecution execution) {
        return WorkflowExecutionDto.builder()
                .id(execution.getId())
                .workflowId(execution.getWorkflowId())
                .tenantId(execution.getTenantId())
                .status(execution.getStatus())
                .triggeredBy(execution.getTriggeredBy())
                .currentStepId(execution.getCurrentStepId())
                .errorMessage(execution.getErrorMessage())
                .startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt())
                .createdAt(execution.getCreatedAt())
                .build();
    }
}
