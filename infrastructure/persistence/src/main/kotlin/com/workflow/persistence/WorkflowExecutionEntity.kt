package com.workflow.persistence

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "workflow_executions")
class WorkflowExecutionEntity(
    @Id
    val id: String,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: String,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val status: ExecutionStatusJpa,

    @Column(name = "context_data", columnDefinition = "TEXT")
    val contextData: String,

    @Column(name = "step_results", columnDefinition = "TEXT")
    val stepResults: String = "[]",

    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    val completedAt: Instant? = null,

    @Column(name = "error_message", length = 2000)
    val errorMessage: String? = null
)

enum class ExecutionStatusJpa {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED
}
