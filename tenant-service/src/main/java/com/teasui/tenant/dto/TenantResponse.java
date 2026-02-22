package com.teasui.tenant.dto;

import com.teasui.tenant.domain.SubscriptionPlan;
import com.teasui.tenant.domain.TenantStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantResponse {

    private String id;
    private String name;
    private String slug;
    private String adminEmail;
    private SubscriptionPlan plan;
    private TenantStatus status;
    private Integer maxUsers;
    private Integer maxWorkflows;
    private String customDomain;
    private Instant createdAt;
    private Instant updatedAt;
}
