package com.teasui.crm.workflow.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Represents a single step within a workflow.
 */
@Entity
@Table(name = "workflow_steps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class WorkflowStep {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false)
    private StepType stepType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb")
    private String config;

    @Column(name = "retry_count")
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "timeout_seconds")
    @Builder.Default
    private int timeoutSeconds = 300;

    @Column(name = "on_failure")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OnFailureAction onFailure = OnFailureAction.STOP;

    public enum StepType {
        HTTP_REQUEST,
        EMAIL,
        CONDITION,
        DELAY,
        INTEGRATION,
        NOTIFICATION,
        CUSTOM
    }

    public enum OnFailureAction {
        STOP,
        CONTINUE,
        RETRY
    }
}
