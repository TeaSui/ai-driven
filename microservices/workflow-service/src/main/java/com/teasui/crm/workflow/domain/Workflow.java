package com.teasui.crm.workflow.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a workflow definition with its steps and trigger configuration.
 */
@Entity
@Table(name = "workflows")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Workflow {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private WorkflowStatus status = WorkflowStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_config", columnDefinition = "jsonb")
    private String triggerConfig;

    @OneToMany(mappedBy = "workflow", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepOrder ASC")
    @Builder.Default
    private List<WorkflowStep> steps = new ArrayList<>();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "version")
    @Builder.Default
    private int version = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum WorkflowStatus {
        DRAFT, ACTIVE, INACTIVE, ARCHIVED
    }

    public enum TriggerType {
        MANUAL, SCHEDULED, WEBHOOK, EVENT
    }
}
