package com.teasui.crm.workflow.dto;

import com.teasui.crm.workflow.domain.Workflow;
import com.teasui.crm.workflow.domain.WorkflowStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDto {

    private String id;
    private String tenantId;
    private String name;
    private String description;
    private Workflow.WorkflowStatus status;
    private Workflow.TriggerType triggerType;
    private String triggerConfig;
    private List<StepDto> steps;
    private String createdBy;
    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WorkflowDto from(Workflow workflow) {
        List<StepDto> stepDtos = workflow.getSteps() == null ? List.of() :
                workflow.getSteps().stream().map(StepDto::from).collect(Collectors.toList());

        return WorkflowDto.builder()
                .id(workflow.getId())
                .tenantId(workflow.getTenantId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .status(workflow.getStatus())
                .triggerType(workflow.getTriggerType())
                .triggerConfig(workflow.getTriggerConfig())
                .steps(stepDtos)
                .createdBy(workflow.getCreatedBy())
                .version(workflow.getVersion())
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepDto {
        private String id;
        private String name;
        private String description;
        private int stepOrder;
        private WorkflowStep.StepType stepType;
        private String config;
        private int retryCount;
        private int timeoutSeconds;
        private WorkflowStep.OnFailureAction onFailure;

        public static StepDto from(WorkflowStep step) {
            return StepDto.builder()
                    .id(step.getId())
                    .name(step.getName())
                    .description(step.getDescription())
                    .stepOrder(step.getStepOrder())
                    .stepType(step.getStepType())
                    .config(step.getConfig())
                    .retryCount(step.getRetryCount())
                    .timeoutSeconds(step.getTimeoutSeconds())
                    .onFailure(step.getOnFailure())
                    .build();
        }
    }
}
