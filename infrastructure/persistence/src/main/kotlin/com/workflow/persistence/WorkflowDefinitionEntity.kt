package com.workflow.persistence

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "workflow_definitions")
class WorkflowDefinitionEntity(
    @Id
    val id: String,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,

    @Column(nullable = false)
    val name: String,

    @Column(length = 1000)
    val description: String = "",

    @Column(name = "trigger_type", nullable = false)
    val triggerType: String,

    @Column(name = "trigger_config", columnDefinition = "TEXT")
    val triggerConfig: String,

    @Column(name = "steps_config", columnDefinition = "TEXT", nullable = false)
    val stepsConfig: String,

    @Column(nullable = false)
    val version: Int = 1,

    @Column(nullable = false)
    val enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now()
)
