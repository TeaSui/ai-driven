package com.teasui.workflow.service;

import com.teasui.common.dto.PagedResponse;
import com.teasui.common.event.WorkflowEvent;
import com.teasui.common.exception.ServiceException;
import com.teasui.common.security.TenantContext;
import com.teasui.workflow.domain.*;
import com.teasui.workflow.dto.*;
import com.teasui.workflow.repository.WorkflowDefinitionRepository;
import com.teasui.workflow.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final String WORKFLOW_EVENTS_TOPIC = "workflow-events";

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final KafkaTemplate<String, WorkflowEvent> kafkaTemplate;

    @Transactional
    public WorkflowDefinitionResponse createWorkflow(CreateWorkflowRequest request) {
        String tenantId = requireTenantId();

        if (definitionRepository.existsByTenantIdAndNameAndVersion(tenantId, request.getName(), 1)) {
            throw ServiceException.conflict("Workflow '" + request.getName() + "' already exists for this tenant");
        }

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .tenantId(tenantId)
                .name(request.getName())
                .description(request.getDescription())
                .steps(request.getSteps())
                .triggerType(request.getTriggerType())
                .triggerConfig(request.getTriggerConfig())
                .createdBy(TenantContext.getUserId())
                .build();

        WorkflowDefinition saved = definitionRepository.save(definition);
        log.info("Created workflow definition: {} for tenant: {}", saved.getId(), tenantId);

        return toDefinitionResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionResponse> getWorkflowsByTenant() {
        String tenantId = requireTenantId();
        return definitionRepository.findAllByTenantId(tenantId)
                .stream()
                .map(this::toDefinitionResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public WorkflowDefinitionResponse getWorkflowById(String id) {
        String tenantId = requireTenantId();
        return definitionRepository.findByTenantIdAndId(tenantId, id)
                .map(this::toDefinitionResponse)
                .orElseThrow(() -> ServiceException.notFound("Workflow", id));
    }

    @Transactional
    public WorkflowDefinitionResponse activateWorkflow(String id) {
        String tenantId = requireTenantId();
        WorkflowDefinition definition = definitionRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ServiceException.notFound("Workflow", id));

        if (definition.getSteps() == null || definition.getSteps().isEmpty()) {
            throw ServiceException.badRequest("Cannot activate workflow with no steps");
        }

        definition.setStatus(WorkflowStatus.ACTIVE);
        WorkflowDefinition saved = definitionRepository.save(definition);
        log.info("Activated workflow: {} for tenant: {}", id, tenantId);

        return toDefinitionResponse(saved);
    }

    @Transactional
    public WorkflowExecutionResponse triggerWorkflow(String workflowId, String context) {
        String tenantId = requireTenantId();
        WorkflowDefinition definition = definitionRepository.findByTenantIdAndId(tenantId, workflowId)
                .orElseThrow(() -> ServiceException.notFound("Workflow", workflowId));

        if (!WorkflowStatus.ACTIVE.equals(definition.getStatus())) {
            throw ServiceException.badRequest("Workflow is not active");
        }

        WorkflowExecution execution = WorkflowExecution.builder()
                .workflowDefinitionId(workflowId)
                .tenantId(tenantId)
                .status(ExecutionStatus.RUNNING)
                .context(context)
                .triggeredBy(TenantContext.getUserId())
                .build();

        WorkflowExecution saved = executionRepository.save(execution);
        log.info("Triggered workflow execution: {} for workflow: {}", saved.getId(), workflowId);

        WorkflowEvent event = WorkflowEvent.workflowStarted(tenantId, workflowId, definition.getName());
        kafkaTemplate.send(WORKFLOW_EVENTS_TOPIC, workflowId, event);

        return toExecutionResponse(saved);
    }

    @Transactional(readOnly = true)
    public PagedResponse<WorkflowExecutionResponse> getExecutions(String workflowId, int page, int size) {
        String tenantId = requireTenantId();
        Page<WorkflowExecution> executions = executionRepository
                .findAllByTenantIdAndWorkflowDefinitionId(tenantId, workflowId, PageRequest.of(page, size));

        List<WorkflowExecutionResponse> content = executions.getContent()
                .stream()
                .map(this::toExecutionResponse)
                .collect(Collectors.toList());

        return PagedResponse.<WorkflowExecutionResponse>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(executions.getTotalElements())
                .totalPages(executions.getTotalPages())
                .first(executions.isFirst())
                .last(executions.isLast())
                .build();
    }

    private String requireTenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw ServiceException.badRequest("Tenant context is required");
        }
        return tenantId;
    }

    private WorkflowDefinitionResponse toDefinitionResponse(WorkflowDefinition d) {
        return WorkflowDefinitionResponse.builder()
                .id(d.getId())
                .tenantId(d.getTenantId())
                .name(d.getName())
                .description(d.getDescription())
                .version(d.getVersion())
                .status(d.getStatus())
                .steps(d.getSteps())
                .triggerType(d.getTriggerType())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .createdBy(d.getCreatedBy())
                .build();
    }

    private WorkflowExecutionResponse toExecutionResponse(WorkflowExecution e) {
        return WorkflowExecutionResponse.builder()
                .id(e.getId())
                .workflowDefinitionId(e.getWorkflowDefinitionId())
                .tenantId(e.getTenantId())
                .status(e.getStatus())
                .currentStepId(e.getCurrentStepId())
                .errorMessage(e.getErrorMessage())
                .startedAt(e.getStartedAt())
                .completedAt(e.getCompletedAt())
                .triggeredBy(e.getTriggeredBy())
                .build();
    }
}
