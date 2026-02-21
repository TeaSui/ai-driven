package com.teasui.crm.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teasui.crm.common.exception.ServiceException;
import com.teasui.crm.workflow.domain.Workflow;
import com.teasui.crm.workflow.domain.WorkflowExecution;
import com.teasui.crm.workflow.dto.CreateWorkflowRequest;
import com.teasui.crm.workflow.dto.WorkflowDto;
import com.teasui.crm.workflow.dto.WorkflowExecutionDto;
import com.teasui.crm.workflow.repository.WorkflowExecutionRepository;
import com.teasui.crm.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowService Tests")
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private WorkflowService workflowService;

    private ObjectMapper objectMapper;
    private static final String TENANT_ID = "tenant-001";
    private static final String USER_ID = "user-001";
    private static final String WORKFLOW_ID = "workflow-001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Inject objectMapper via reflection since @InjectMocks doesn't handle it
        try {
            var field = WorkflowService.class.getDeclaredField("objectMapper");
            field.setAccessible(true);
            field.set(workflowService, objectMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should create workflow successfully")
    void shouldCreateWorkflowSuccessfully() {
        CreateWorkflowRequest request = new CreateWorkflowRequest();
        request.setName("Test Workflow");
        request.setDescription("A test workflow");
        request.setTriggerType(Workflow.TriggerType.MANUAL);

        Workflow savedWorkflow = Workflow.builder()
                .id(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .name("Test Workflow")
                .description("A test workflow")
                .triggerType(Workflow.TriggerType.MANUAL)
                .createdBy(USER_ID)
                .build();

        when(workflowRepository.existsByNameAndTenantId(anyString(), anyString())).thenReturn(false);
        when(workflowRepository.save(any(Workflow.class))).thenReturn(savedWorkflow);

        WorkflowDto result = workflowService.createWorkflow(TENANT_ID, USER_ID, request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(WORKFLOW_ID);
        assertThat(result.getName()).isEqualTo("Test Workflow");
        assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
        verify(workflowRepository).save(any(Workflow.class));
    }

    @Test
    @DisplayName("Should throw conflict when workflow name already exists")
    void shouldThrowConflictWhenWorkflowNameExists() {
        CreateWorkflowRequest request = new CreateWorkflowRequest();
        request.setName("Existing Workflow");
        request.setTriggerType(Workflow.TriggerType.MANUAL);

        when(workflowRepository.existsByNameAndTenantId(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> workflowService.createWorkflow(TENANT_ID, USER_ID, request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("already exists");

        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should get workflow by id")
    void shouldGetWorkflowById() {
        Workflow workflow = Workflow.builder()
                .id(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .name("Test Workflow")
                .triggerType(Workflow.TriggerType.MANUAL)
                .createdBy(USER_ID)
                .build();

        when(workflowRepository.findByIdAndTenantId(WORKFLOW_ID, TENANT_ID))
                .thenReturn(Optional.of(workflow));

        WorkflowDto result = workflowService.getWorkflow(TENANT_ID, WORKFLOW_ID);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    @DisplayName("Should throw not found when workflow does not exist")
    void shouldThrowNotFoundWhenWorkflowDoesNotExist() {
        when(workflowRepository.findByIdAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.getWorkflow(TENANT_ID, "non-existent"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("Should activate workflow")
    void shouldActivateWorkflow() {
        Workflow workflow = Workflow.builder()
                .id(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .name("Test Workflow")
                .triggerType(Workflow.TriggerType.MANUAL)
                .status(Workflow.WorkflowStatus.DRAFT)
                .createdBy(USER_ID)
                .steps(List.of())
                .build();

        // Add a step to allow activation
        com.teasui.crm.workflow.domain.WorkflowStep step =
                com.teasui.crm.workflow.domain.WorkflowStep.builder()
                        .id("step-001")
                        .name("Step 1")
                        .stepType(com.teasui.crm.workflow.domain.WorkflowStep.StepType.HTTP_REQUEST)
                        .stepOrder(1)
                        .build();
        workflow.setSteps(List.of(step));

        Workflow activatedWorkflow = Workflow.builder()
                .id(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .name("Test Workflow")
                .triggerType(Workflow.TriggerType.MANUAL)
                .status(Workflow.WorkflowStatus.ACTIVE)
                .createdBy(USER_ID)
                .steps(List.of(step))
                .build();

        when(workflowRepository.findByIdAndTenantId(WORKFLOW_ID, TENANT_ID))
                .thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenReturn(activatedWorkflow);

        WorkflowDto result = workflowService.activateWorkflow(TENANT_ID, WORKFLOW_ID);

        assertThat(result.getStatus()).isEqualTo(Workflow.WorkflowStatus.ACTIVE);
    }

    @Test
    @DisplayName("Should throw bad request when activating workflow with no steps")
    void shouldThrowBadRequestWhenActivatingWorkflowWithNoSteps() {
        Workflow workflow = Workflow.builder()
                .id(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .name("Test Workflow")
                .triggerType(Workflow.TriggerType.MANUAL)
                .status(Workflow.WorkflowStatus.DRAFT)
                .createdBy(USER_ID)
                .steps(List.of())
                .build();

        when(workflowRepository.findByIdAndTenantId(WORKFLOW_ID, TENANT_ID))
                .thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> workflowService.activateWorkflow(TENANT_ID, WORKFLOW_ID))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("no steps");
    }

    @Test
    @DisplayName("Should trigger workflow execution")
    void shouldTriggerWorkflowExecution() {
        com.teasui.crm.workflow.domain.WorkflowStep step =
                com.teasui.crm.workflow.domain.WorkflowStep.builder()
                        .id("step-001")
                        .name("Step 1")
                        .stepType(com.teasui.crm.workflow.domain.WorkflowStep.StepType.HTTP_REQUEST)
                        .stepOrder(1)
                        .build();

        Workflow workflow = Workflow.builder()
                .id(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .name("Test Workflow")
                .triggerType(Workflow.TriggerType.MANUAL)
                .status(Workflow.WorkflowStatus.ACTIVE)
                .createdBy(USER_ID)
                .steps(List.of(step))
                .build();

        WorkflowExecution savedExecution = WorkflowExecution.builder()
                .id("exec-001")
                .workflowId(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .triggeredBy(USER_ID)
                .status(WorkflowExecution.ExecutionStatus.PENDING)
                .build();

        when(workflowRepository.findByIdAndTenantId(WORKFLOW_ID, TENANT_ID))
                .thenReturn(Optional.of(workflow));
        when(executionRepository.save(any(WorkflowExecution.class))).thenReturn(savedExecution);
        when(executionRepository.findById(anyString())).thenReturn(Optional.of(savedExecution));

        WorkflowExecutionDto result = workflowService.triggerWorkflow(
                TENANT_ID, WORKFLOW_ID, USER_ID, Map.of("key", "value"));

        assertThat(result).isNotNull();
        assertThat(result.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        verify(executionRepository).save(any(WorkflowExecution.class));
    }

    @Test
    @DisplayName("Should list workflows with pagination")
    void shouldListWorkflowsWithPagination() {
        Workflow workflow = Workflow.builder()
                .id(WORKFLOW_ID)
                .tenantId(TENANT_ID)
                .name("Test Workflow")
                .triggerType(Workflow.TriggerType.MANUAL)
                .createdBy(USER_ID)
                .build();

        when(workflowRepository.findByTenantId(eq(TENANT_ID), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(workflow)));

        var result = workflowService.listWorkflows(TENANT_ID, 0, 20);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }
}
