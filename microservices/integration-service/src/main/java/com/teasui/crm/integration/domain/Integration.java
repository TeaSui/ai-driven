package com.teasui.crm.integration.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a configured integration with a third-party tool.
 * Each tenant can have multiple integrations of different types.
 */
@Entity
@Table(name = "integrations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Integration {

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
    @Column(name = "type", nullable = false)
    private IntegrationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private IntegrationStatus status = IntegrationStatus.INACTIVE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb")
    private String config;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credentials", columnDefinition = "jsonb")
    private String credentials;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum IntegrationType {
        SLACK,
        JIRA,
        GITHUB,
        SALESFORCE,
        HUBSPOT,
        ZAPIER,
        WEBHOOK,
        REST_API,
        EMAIL_SMTP,
        CUSTOM
    }

    public enum IntegrationStatus {
        ACTIVE, INACTIVE, ERROR
    }
}
