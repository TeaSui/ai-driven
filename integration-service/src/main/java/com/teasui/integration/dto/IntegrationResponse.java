package com.teasui.integration.dto;

import com.teasui.integration.domain.IntegrationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationResponse {

    private String id;
    private String tenantId;
    private String name;
    private String provider;
    private IntegrationStatus status;
    private String webhookUrl;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
