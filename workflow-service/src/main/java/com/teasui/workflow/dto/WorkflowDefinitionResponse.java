package com.teasui.workflow.dto;

import com.teasui.workflow.domain.WorkflowStatus;
import com.teasui.workflow.domain.WorkflowStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinitionResponse {

    private String id;
    private String tenantId;
    private String name;
    private String description;
    private Integer version;
    private WorkflowStatus status;
    private List<WorkflowStep> steps;
    private String triggerType;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
}
