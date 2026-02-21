package com.teasui.crm.workflow.controller;

import com.teasui.crm.common.dto.ApiResponse;
import com.teasui.crm.common.dto.PageResponse;
import com.teasui.crm.workflow.dto.CreateWorkflowRequest;
import com.teasui.crm.workflow.dto.WorkflowDto;
import com.teasui.crm.workflow.dto.WorkflowExecutionDto;
import com.teasui.crm.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    public ResponseEntity<ApiResponse<WorkflowDto>> createWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowDto workflow = workflowService.createWorkflow(tenantId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(workflow, "Workflow created successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<WorkflowDto>>> listWorkflows(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<WorkflowDto> workflows = workflowService.listWorkflows(tenantId, page, size);
        return ResponseEntity.ok(ApiResponse.success(workflows));
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<ApiResponse<WorkflowDto>> getWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId) {
        WorkflowDto workflow = workflowService.getWorkflow(tenantId, workflowId);
        return ResponseEntity.ok(ApiResponse.success(workflow));
    }

    @PutMapping("/{workflowId}/activate")
    public ResponseEntity<ApiResponse<WorkflowDto>> activateWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId) {
        WorkflowDto workflow = workflowService.activateWorkflow(tenantId, workflowId);
        return ResponseEntity.ok(ApiResponse.success(workflow, "Workflow activated"));
    }

    @PostMapping("/{workflowId}/trigger")
    public ResponseEntity<ApiResponse<WorkflowExecutionDto>> triggerWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String workflowId,
            @RequestBody(required = false) Map<String, Object> context) {
        WorkflowExecutionDto execution = workflowService.triggerWorkflow(tenantId, workflowId, userId, context);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(execution, "Workflow triggered successfully"));
    }
}
