package com.workflow.engine

import com.workflow.api.TenantId
import com.workflow.api.WorkflowDefinition
import com.workflow.api.WorkflowId
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for persisting workflow definitions.
 */
interface WorkflowDefinitionRepository {
    fun save(workflow: WorkflowDefinition): WorkflowDefinition
    fun findById(id: WorkflowId): WorkflowDefinition?
    fun findByTenantId(tenantId: TenantId): List<WorkflowDefinition>
    fun findEnabledByTenantId(tenantId: TenantId): List<WorkflowDefinition>
    fun delete(id: WorkflowId)
    fun existsById(id: WorkflowId): Boolean
}

/**
 * In-memory implementation for testing and development.
 */
class InMemoryWorkflowDefinitionRepository : WorkflowDefinitionRepository {

    private val store = ConcurrentHashMap<String, WorkflowDefinition>()

    override fun save(workflow: WorkflowDefinition): WorkflowDefinition {
        store[workflow.id.value] = workflow
        return workflow
    }

    override fun findById(id: WorkflowId): WorkflowDefinition? = store[id.value]

    override fun findByTenantId(tenantId: TenantId): List<WorkflowDefinition> =
        store.values.filter { it.tenantId == tenantId }

    override fun findEnabledByTenantId(tenantId: TenantId): List<WorkflowDefinition> =
        store.values.filter { it.tenantId == tenantId && it.enabled }

    override fun delete(id: WorkflowId) {
        store.remove(id.value)
    }

    override fun existsById(id: WorkflowId): Boolean = store.containsKey(id.value)
}
