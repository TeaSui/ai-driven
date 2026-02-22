package com.teasui.workflow.dto;

import com.teasui.workflow.domain.WorkflowStep;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateWorkflowRequest {

    @NotBlank(message = "Workflow name is required")
    private String name;

    private String description;

    @NotEmpty(message = "At least one step is required")
    private List<WorkflowStep> steps;

    private String triggerType;
    private String triggerConfig;
}
