package com.teasui.workflow.controller;

import com.teasui.common.dto.ApiResponse;
import com.teasui.common.dto.PagedResponse;
import com.teasui.workflow.dto.*;
import com.teasui.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> createWorkflow(
            @Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowDefinitionResponse response = workflowService.createWorkflow(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Workflow created successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<WorkflowDefinitionResponse>>> getWorkflows() {
        List<WorkflowDefinitionResponse> workflows = workflowService.getWorkflowsByTenant();
        return ResponseEntity.ok(ApiResponse.success(workflows));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> getWorkflow(@PathVariable String id) {
        WorkflowDefinitionResponse response = workflowService.getWorkflowById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> activateWorkflow(@PathVariable String id) {
        WorkflowDefinitionResponse response = workflowService.activateWorkflow(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Workflow activated"));
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<ApiResponse<WorkflowExecutionResponse>> triggerWorkflow(
            @PathVariable String id,
            @RequestBody(required = false) String context) {
        WorkflowExecutionResponse response = workflowService.triggerWorkflow(id, context);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(response, "Workflow triggered"));
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<PagedResponse<WorkflowExecutionResponse>>> getExecutions(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<WorkflowExecutionResponse> response = workflowService.getExecutions(id, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
