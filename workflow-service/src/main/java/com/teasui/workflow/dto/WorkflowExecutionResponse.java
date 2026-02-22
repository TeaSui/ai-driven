package com.teasui.workflow.dto;

import com.teasui.workflow.domain.ExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowExecutionResponse {

    private String id;
    private String workflowDefinitionId;
    private String tenantId;
    private ExecutionStatus status;
    private String currentStepId;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    private String triggeredBy;
}
