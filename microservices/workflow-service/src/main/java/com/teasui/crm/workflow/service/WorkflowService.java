package com.teasui.crm.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teasui.crm.common.dto.PageResponse;
import com.teasui.crm.common.event.workflow.WorkflowExecutionEvent;
import com.teasui.crm.common.exception.ServiceException;
import com.teasui.crm.common.messaging.RabbitMQConfig;
import com.teasui.crm.workflow.domain.Workflow;
import com.teasui.crm.workflow.domain.WorkflowExecution;
import com.teasui.crm.workflow.domain.WorkflowStep;
import com.teasui.crm.workflow.dto.CreateWorkflowRequest;
import com.teasui.crm.workflow.dto.WorkflowDto;
import com.teasui.crm.workflow.dto.WorkflowExecutionDto;
import com.teasui.crm.workflow.repository.WorkflowExecutionRepository;
import com.teasui.crm.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final String SERVICE_NAME = "workflow-service";

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowDto createWorkflow(String tenantId, String userId, CreateWorkflowRequest request) {
        if (workflowRepository.existsByNameAndTenantId(request.getName(), tenantId)) {
            throw ServiceException.conflict("Workflow with name '" + request.getName() + "' already exists");
        }

        Workflow workflow = Workflow.builder()
                .tenantId(tenantId)
                .name(request.getName())
                .description(request.getDescription())
                .triggerType(request.getTriggerType())
                .triggerConfig(request.getTriggerConfig())
                .createdBy(userId)
                .build();

        if (request.getSteps() != null) {
            List<WorkflowStep> steps = request.getSteps().stream()
                    .map(stepReq -> WorkflowStep.builder()
                            .workflow(workflow)
                            .name(stepReq.getName())
                            .description(stepReq.getDescription())
                            .stepOrder(stepReq.getStepOrder())
                            .stepType(stepReq.getStepType())
                            .config(stepReq.getConfig())
                            .retryCount(stepReq.getRetryCount())
                            .timeoutSeconds(stepReq.getTimeoutSeconds())
                            .onFailure(stepReq.getOnFailure())
                            .build())
                    .collect(Collectors.toList());
            workflow.setSteps(steps);
        }

        Workflow saved = workflowRepository.save(workflow);
        log.info("Created workflow '{}' for tenant '{}'", saved.getName(), tenantId);
        return WorkflowDto.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowDto> listWorkflows(String tenantId, int page, int size) {
        Page<Workflow> workflowPage = workflowRepository.findByTenantId(
                tenantId, PageRequest.of(page, size));
        List<WorkflowDto> dtos = workflowPage.getContent().stream()
                .map(WorkflowDto::from)
                .collect(Collectors.toList());
        return PageResponse.of(dtos, page, size, workflowPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public WorkflowDto getWorkflow(String tenantId, String workflowId) {
        Workflow workflow = workflowRepository.findByIdAndTenantId(workflowId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("Workflow", workflowId));
        return WorkflowDto.from(workflow);
    }

    @Transactional
    public WorkflowDto activateWorkflow(String tenantId, String workflowId) {
        Workflow workflow = workflowRepository.findByIdAndTenantId(workflowId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("Workflow", workflowId));

        if (workflow.getSteps().isEmpty()) {
            throw ServiceException.badRequest("Cannot activate a workflow with no steps");
        }

        workflow.setStatus(Workflow.WorkflowStatus.ACTIVE);
        Workflow saved = workflowRepository.save(workflow);
        log.info("Activated workflow '{}' for tenant '{}'", workflowId, tenantId);
        return WorkflowDto.from(saved);
    }

    @Transactional
    public WorkflowExecutionDto triggerWorkflow(String tenantId, String workflowId, String triggeredBy, Map<String, Object> inputContext) {
        Workflow workflow = workflowRepository.findByIdAndTenantId(workflowId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("Workflow", workflowId));

        if (workflow.getStatus() != Workflow.WorkflowStatus.ACTIVE) {
            throw ServiceException.badRequest("Workflow is not active");
        }

        String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(inputContext != null ? inputContext : new HashMap<>());
        } catch (Exception e) {
            contextJson = "{}";
        }

        WorkflowExecution execution = WorkflowExecution.builder()
                .workflowId(workflowId)
                .tenantId(tenantId)
                .triggeredBy(triggeredBy)
                .context(contextJson)
                .status(WorkflowExecution.ExecutionStatus.PENDING)
                .build();

        execution = executionRepository.save(execution);

        // Publish event to start async execution
        publishExecutionEvent(execution, workflow, WorkflowExecutionEvent.ExecutionStatus.STARTED);

        // Execute asynchronously
        executeWorkflowAsync(execution.getId(), workflow);

        log.info("Triggered workflow '{}' execution '{}' for tenant '{}'",
                workflowId, execution.getId(), tenantId);

        return WorkflowExecutionDto.from(execution);
    }

    @Async
    public void executeWorkflowAsync(String executionId, Workflow workflow) {
        WorkflowExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) return;

        try {
            execution.setStatus(WorkflowExecution.ExecutionStatus.RUNNING);
            execution.setStartedAt(LocalDateTime.now());
            executionRepository.save(execution);

            for (WorkflowStep step : workflow.getSteps()) {
                execution.setCurrentStepId(step.getId());
                executionRepository.save(execution);

                log.info("Executing step '{}' of workflow '{}'", step.getName(), workflow.getId());
                executeStep(step, execution);

                publishStepCompletedEvent(execution, workflow, step);
            }

            execution.setStatus(WorkflowExecution.ExecutionStatus.COMPLETED);
            execution.setCompletedAt(LocalDateTime.now());
            execution.setCurrentStepId(null);
            executionRepository.save(execution);

            publishExecutionEvent(execution, workflow, WorkflowExecutionEvent.ExecutionStatus.COMPLETED);
            log.info("Workflow execution '{}' completed successfully", executionId);

        } catch (Exception e) {
            log.error("Workflow execution '{}' failed: {}", executionId, e.getMessage(), e);
            execution.setStatus(WorkflowExecution.ExecutionStatus.FAILED);
            execution.setErrorMessage(e.getMessage());
            execution.setCompletedAt(LocalDateTime.now());
            executionRepository.save(execution);

            publishExecutionEvent(execution, workflow, WorkflowExecutionEvent.ExecutionStatus.FAILED);
        }
    }

    private void executeStep(WorkflowStep step, WorkflowExecution execution) {
        // Step execution logic - extensible per step type
        log.debug("Executing step type '{}': '{}'", step.getStepType(), step.getName());
        // Each step type would have its own executor implementation
        // This is the extension point for integrations
    }

    private void publishExecutionEvent(WorkflowExecution execution, Workflow workflow,
                                       WorkflowExecutionEvent.ExecutionStatus status) {
        try {
            WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                    .workflowId(workflow.getId())
                    .executionId(execution.getId())
                    .workflowName(workflow.getName())
                    .status(status)
                    .tenantId(execution.getTenantId())
                    .triggeredBy(execution.getTriggeredBy())
                    .errorMessage(execution.getErrorMessage())
                    .build();
            event.initDefaults(SERVICE_NAME);

            String routingKey = switch (status) {
                case STARTED -> RabbitMQConfig.WORKFLOW_STARTED_KEY;
                case COMPLETED -> RabbitMQConfig.WORKFLOW_COMPLETED_KEY;
                case FAILED -> RabbitMQConfig.WORKFLOW_FAILED_KEY;
                default -> RabbitMQConfig.WORKFLOW_STARTED_KEY;
            };

            rabbitTemplate.convertAndSend(RabbitMQConfig.WORKFLOW_EXCHANGE, routingKey, event);
        } catch (Exception e) {
            log.error("Failed to publish workflow execution event: {}", e.getMessage());
        }
    }

    private void publishStepCompletedEvent(WorkflowExecution execution, Workflow workflow, WorkflowStep step) {
        try {
            WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                    .workflowId(workflow.getId())
                    .executionId(execution.getId())
                    .workflowName(workflow.getName())
                    .status(WorkflowExecutionEvent.ExecutionStatus.STEP_COMPLETED)
                    .currentStepId(step.getId())
                    .currentStepName(step.getName())
                    .tenantId(execution.getTenantId())
                    .build();
            event.initDefaults(SERVICE_NAME);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.WORKFLOW_EXCHANGE,
                    RabbitMQConfig.WORKFLOW_STEP_COMPLETED_KEY,
                    event);
        } catch (Exception e) {
            log.error("Failed to publish step completed event: {}", e.getMessage());
        }
    }
}
