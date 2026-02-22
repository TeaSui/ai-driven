package com.teasui.integration.service;

import com.teasui.common.exception.ServiceException;
import com.teasui.common.security.TenantContext;
import com.teasui.integration.domain.Integration;
import com.teasui.integration.domain.IntegrationStatus;
import com.teasui.integration.dto.CreateIntegrationRequest;
import com.teasui.integration.dto.IntegrationResponse;
import com.teasui.integration.repository.IntegrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationRepository integrationRepository;

    @Transactional
    public IntegrationResponse createIntegration(CreateIntegrationRequest request) {
        String tenantId = requireTenantId();

        if (integrationRepository.existsByTenantIdAndProviderAndName(tenantId, request.getProvider(), request.getName())) {
            throw ServiceException.conflict(
                    "Integration '" + request.getName() + "' for provider '" + request.getProvider() + "' already exists");
        }

        Integration integration = Integration.builder()
                .tenantId(tenantId)
                .name(request.getName())
                .provider(request.getProvider())
                .config(request.getConfig())
                .webhookUrl(request.getWebhookUrl())
                .build();

        Integration saved = integrationRepository.save(integration);
        log.info("Created integration: {} ({}) for tenant: {}", saved.getName(), saved.getProvider(), tenantId);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<IntegrationResponse> getIntegrationsByTenant() {
        String tenantId = requireTenantId();
        return integrationRepository.findAllByTenantId(tenantId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IntegrationResponse getIntegrationById(String id) {
        String tenantId = requireTenantId();
        return integrationRepository.findByTenantIdAndId(tenantId, id)
                .map(this::toResponse)
                .orElseThrow(() -> ServiceException.notFound("Integration", id));
    }

    @Transactional
    public IntegrationResponse activateIntegration(String id) {
        String tenantId = requireTenantId();
        Integration integration = integrationRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ServiceException.notFound("Integration", id));

        integration.setStatus(IntegrationStatus.ACTIVE);
        integration.setLastSyncedAt(Instant.now());
        Integration saved = integrationRepository.save(integration);
        log.info("Activated integration: {} for tenant: {}", id, tenantId);

        return toResponse(saved);
    }

    @Transactional
    public void deleteIntegration(String id) {
        String tenantId = requireTenantId();
        Integration integration = integrationRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ServiceException.notFound("Integration", id));

        integrationRepository.delete(integration);
        log.info("Deleted integration: {} for tenant: {}", id, tenantId);
    }

    private String requireTenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw ServiceException.badRequest("Tenant context is required");
        }
        return tenantId;
    }

    private IntegrationResponse toResponse(Integration i) {
        return IntegrationResponse.builder()
                .id(i.getId())
                .tenantId(i.getTenantId())
                .name(i.getName())
                .provider(i.getProvider())
                .status(i.getStatus())
                .webhookUrl(i.getWebhookUrl())
                .lastSyncedAt(i.getLastSyncedAt())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .build();
    }
}
