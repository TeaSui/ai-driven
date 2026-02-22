package com.workflow.engine

import com.workflow.api.ExecutionId
import com.workflow.api.ExecutionStatus
import com.workflow.api.TenantId
import com.workflow.api.WorkflowExecution
import com.workflow.api.WorkflowId
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for persisting workflow executions.
 */
interface WorkflowExecutionRepository {
    fun save(execution: WorkflowExecution): WorkflowExecution
    fun findById(id: ExecutionId): WorkflowExecution?
    fun findByWorkflowId(workflowId: WorkflowId): List<WorkflowExecution>
    fun findByTenantId(tenantId: TenantId): List<WorkflowExecution>
    fun findByStatus(status: ExecutionStatus): List<WorkflowExecution>
    fun findByTenantAndStatus(tenantId: TenantId, status: ExecutionStatus): List<WorkflowExecution>
    fun delete(id: ExecutionId)
}

/**
 * In-memory implementation for testing and development.
 */
class InMemoryWorkflowExecutionRepository : WorkflowExecutionRepository {

    private val store = ConcurrentHashMap<String, WorkflowExecution>()

    override fun save(execution: WorkflowExecution): WorkflowExecution {
        store[execution.id.value] = execution
        return execution
    }

    override fun findById(id: ExecutionId): WorkflowExecution? = store[id.value]

    override fun findByWorkflowId(workflowId: WorkflowId): List<WorkflowExecution> =
        store.values.filter { it.workflowId == workflowId }

    override fun findByTenantId(tenantId: TenantId): List<WorkflowExecution> =
        store.values.filter { it.tenantId == tenantId }

    override fun findByStatus(status: ExecutionStatus): List<WorkflowExecution> =
        store.values.filter { it.status == status }

    override fun findByTenantAndStatus(tenantId: TenantId, status: ExecutionStatus): List<WorkflowExecution> =
        store.values.filter { it.tenantId == tenantId && it.status == status }

    override fun delete(id: ExecutionId) {
        store.remove(id.value)
    }
}
