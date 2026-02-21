package com.teasui.crm.workflow.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks the execution state of a workflow instance.
 */
@Entity
@Table(name = "workflow_executions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class WorkflowExecution {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "current_step_id")
    private String currentStepId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", columnDefinition = "jsonb")
    private String context;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ExecutionStatus {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }
}
