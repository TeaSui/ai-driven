package com.workflow.app.api

import com.workflow.api.*
import com.workflow.engine.WorkflowDefinitionRepository
import com.workflow.engine.WorkflowEngine
import com.workflow.engine.WorkflowExecutionRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/workflows")
class WorkflowController(
    private val workflowEngine: WorkflowEngine,
    private val workflowDefinitionRepository: WorkflowDefinitionRepository,
    private val workflowExecutionRepository: WorkflowExecutionRepository
) {

    @PostMapping
    fun createWorkflow(
        @PathVariable tenantId: String,
        @Valid @RequestBody request: CreateWorkflowRequest
    ): ResponseEntity<WorkflowResponse> {
        val workflow = WorkflowDefinition(
            id = WorkflowId.generate(),
            tenantId = TenantId.of(tenantId),
            name = request.name,
            description = request.description ?: "",
            trigger = request.trigger,
            steps = request.steps
        )
        workflowDefinitionRepository.save(workflow)
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowResponse.from(workflow))
    }

    @GetMapping
    fun listWorkflows(
        @PathVariable tenantId: String
    ): ResponseEntity<List<WorkflowResponse>> {
        val workflows = workflowDefinitionRepository.findByTenantId(TenantId.of(tenantId))
        return ResponseEntity.ok(workflows.map { WorkflowResponse.from(it) })
    }

    @GetMapping("/{workflowId}")
    fun getWorkflow(
        @PathVariable tenantId: String,
        @PathVariable workflowId: String
    ): ResponseEntity<WorkflowResponse> {
        val workflow = workflowDefinitionRepository.findById(WorkflowId.of(workflowId))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(WorkflowResponse.from(workflow))
    }

    @PostMapping("/{workflowId}/trigger")
    fun triggerWorkflow(
        @PathVariable tenantId: String,
        @PathVariable workflowId: String,
        @RequestBody triggerData: Map<String, Any> = emptyMap()
    ): ResponseEntity<TriggerResponse> {
        val workflow = workflowDefinitionRepository.findById(WorkflowId.of(workflowId))
            ?: return ResponseEntity.notFound().build()

        val executionId = workflowEngine.trigger(workflow, triggerData)
        return ResponseEntity.accepted().body(TriggerResponse(executionId.value))
    }

    @GetMapping("/{workflowId}/executions")
    fun listExecutions(
        @PathVariable tenantId: String,
        @PathVariable workflowId: String
    ): ResponseEntity<List<ExecutionResponse>> {
        val executions = workflowExecutionRepository.findByWorkflowId(WorkflowId.of(workflowId))
        return ResponseEntity.ok(executions.map { ExecutionResponse.from(it) })
    }

    @DeleteMapping("/{workflowId}")
    fun deleteWorkflow(
        @PathVariable tenantId: String,
        @PathVariable workflowId: String
    ): ResponseEntity<Void> {
        if (!workflowDefinitionRepository.existsById(WorkflowId.of(workflowId))) {
            return ResponseEntity.notFound().build()
        }
        workflowDefinitionRepository.delete(WorkflowId.of(workflowId))
        return ResponseEntity.noContent().build()
    }
}

// Request/Response DTOs
data class CreateWorkflowRequest(
    @field:NotBlank val name: String,
    val description: String? = null,
    val trigger: TriggerDefinition,
    val steps: List<StepDefinition>
)

data class WorkflowResponse(
    val id: String,
    val tenantId: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val stepCount: Int,
    val version: Int
) {
    companion object {
        fun from(workflow: WorkflowDefinition) = WorkflowResponse(
            id = workflow.id.value,
            tenantId = workflow.tenantId.value,
            name = workflow.name,
            description = workflow.description,
            enabled = workflow.enabled,
            stepCount = workflow.steps.size,
            version = workflow.version
        )
    }
}

data class TriggerResponse(val executionId: String)

data class ExecutionResponse(
    val id: String,
    val workflowId: String,
    val status: String,
    val stepCount: Int,
    val startedAt: String,
    val completedAt: String?,
    val error: String?
) {
    companion object {
        fun from(execution: WorkflowExecution) = ExecutionResponse(
            id = execution.id.value,
            workflowId = execution.workflowId.value,
            status = execution.status.name,
            stepCount = execution.stepResults.size,
            startedAt = execution.startedAt.toString(),
            completedAt = execution.completedAt?.toString(),
            error = execution.error
        )
    }
}
