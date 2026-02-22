package com.teasui.tenant.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

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
    private String id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenantStatus status;

    @Column(name = "max_users")
    private Integer maxUsers;

    @Column(name = "max_workflows")
    private Integer maxWorkflows;

    @Column(name = "custom_domain")
    private String customDomain;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.status == null) {
            this.status = TenantStatus.ACTIVE;
        }
    }

    public boolean isActive() {
        return TenantStatus.ACTIVE.equals(this.status);
    }

    public void suspend() {
        this.status = TenantStatus.SUSPENDED;
        this.suspendedAt = Instant.now();
    }

    public void activate() {
        this.status = TenantStatus.ACTIVE;
        this.suspendedAt = null;
    }
}
