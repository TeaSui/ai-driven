package com.teasui.crm.workflow.dto;

import com.teasui.crm.workflow.domain.Workflow;
import com.teasui.crm.workflow.domain.WorkflowStep;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateWorkflowRequest {

    @NotBlank(message = "Workflow name is required")
    private String name;

    private String description;

    @NotNull(message = "Trigger type is required")
    private Workflow.TriggerType triggerType;

    private String triggerConfig;

    private List<StepRequest> steps;

    @Data
    public static class StepRequest {

        @NotBlank(message = "Step name is required")
        private String name;

        private String description;

        private int stepOrder;

        @NotNull(message = "Step type is required")
        private WorkflowStep.StepType stepType;

        private String config;

        private int retryCount = 0;

        private int timeoutSeconds = 300;

        private WorkflowStep.OnFailureAction onFailure = WorkflowStep.OnFailureAction.STOP;
    }
}
