package com.teasui.crm.auth.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a client/company using the platform.
 * Each tenant has isolated data and can have custom configurations.
 */
@Entity
@Table(name = "tenants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Tenant {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false)
    @Builder.Default
    private TenantPlan plan = TenantPlan.STARTER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TenantStatus status = TenantStatus.ACTIVE;

    @Column(name = "max_users")
    @Builder.Default
    private int maxUsers = 5;

    @Column(name = "max_workflows")
    @Builder.Default
    private int maxWorkflows = 10;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum TenantPlan {
        STARTER, PROFESSIONAL, ENTERPRISE
    }

    public enum TenantStatus {
        ACTIVE, SUSPENDED, CANCELLED
    }
}
