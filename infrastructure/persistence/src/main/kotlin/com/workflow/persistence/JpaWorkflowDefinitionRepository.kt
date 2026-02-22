package com.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.workflow.api.*
import com.workflow.engine.WorkflowDefinitionRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WorkflowDefinitionJpaRepository : JpaRepository<WorkflowDefinitionEntity, String> {
    fun findByTenantId(tenantId: String): List<WorkflowDefinitionEntity>
    fun findByTenantIdAndEnabled(tenantId: String, enabled: Boolean): List<WorkflowDefinitionEntity>
}

@Repository
class JpaWorkflowDefinitionRepository(
    private val jpaRepo: WorkflowDefinitionJpaRepository,
    private val objectMapper: ObjectMapper
) : WorkflowDefinitionRepository {

    override fun save(workflow: WorkflowDefinition): WorkflowDefinition {
        val entity = toEntity(workflow)
        jpaRepo.save(entity)
        return workflow
    }

    override fun findById(id: WorkflowId): WorkflowDefinition? =
        jpaRepo.findById(id.value).map { toDomain(it) }.orElse(null)

    override fun findByTenantId(tenantId: TenantId): List<WorkflowDefinition> =
        jpaRepo.findByTenantId(tenantId.value).map { toDomain(it) }

    override fun findEnabledByTenantId(tenantId: TenantId): List<WorkflowDefinition> =
        jpaRepo.findByTenantIdAndEnabled(tenantId.value, true).map { toDomain(it) }

    override fun delete(id: WorkflowId) = jpaRepo.deleteById(id.value)

    override fun existsById(id: WorkflowId): Boolean = jpaRepo.existsById(id.value)

    private fun toEntity(workflow: WorkflowDefinition): WorkflowDefinitionEntity {
        return WorkflowDefinitionEntity(
            id = workflow.id.value,
            tenantId = workflow.tenantId.value,
            name = workflow.name,
            description = workflow.description,
            triggerType = workflow.trigger.type,
            triggerConfig = objectMapper.writeValueAsString(workflow.trigger),
            stepsConfig = objectMapper.writeValueAsString(workflow.steps),
            version = workflow.version,
            enabled = workflow.enabled,
            createdAt = workflow.createdAt,
            updatedAt = workflow.updatedAt
        )
    }

    private fun toDomain(entity: WorkflowDefinitionEntity): WorkflowDefinition {
        val trigger = objectMapper.readValue<TriggerDefinition>(entity.triggerConfig)
        val steps = objectMapper.readValue<List<StepDefinition>>(entity.stepsConfig)
        return WorkflowDefinition(
            id = WorkflowId.of(entity.id),
            tenantId = TenantId.of(entity.tenantId),
            name = entity.name,
            description = entity.description,
            trigger = trigger,
            steps = steps,
            version = entity.version,
            enabled = entity.enabled,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }
}
