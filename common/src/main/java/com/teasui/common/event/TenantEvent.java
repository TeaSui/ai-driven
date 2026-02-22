package com.teasui.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TenantEvent extends BaseEvent {

    public enum Type {
        TENANT_CREATED,
        TENANT_UPDATED,
        TENANT_SUSPENDED,
        TENANT_ACTIVATED,
        TENANT_DELETED
    }

    private Type type;
    private String tenantName;
    private String plan;
    private String adminEmail;

    public static TenantEvent tenantCreated(String tenantId, String tenantName, String plan, String adminEmail) {
        return TenantEvent.builder()
                .eventId(generateEventId())
                .eventType("TENANT")
                .tenantId(tenantId)
                .occurredAt(Instant.now())
                .version(1)
                .type(Type.TENANT_CREATED)
                .tenantName(tenantName)
                .plan(plan)
                .adminEmail(adminEmail)
                .build();
    }

    public static TenantEvent tenantSuspended(String tenantId) {
        return TenantEvent.builder()
                .eventId(generateEventId())
                .eventType("TENANT")
                .tenantId(tenantId)
                .occurredAt(Instant.now())
                .version(1)
                .type(Type.TENANT_SUSPENDED)
                .build();
    }
}
