package com.teasui.crm.integration.service;

import com.teasui.crm.common.exception.ServiceException;
import com.teasui.crm.integration.domain.Integration;
import com.teasui.crm.integration.plugin.IntegrationPlugin;
import com.teasui.crm.integration.repository.IntegrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationRepository integrationRepository;
    private final List<IntegrationPlugin> plugins;

    public List<Map<String, String>> listAvailablePlugins() {
        return plugins.stream()
                .map(p -> Map.of(
                        "type", p.getPluginType(),
                        "name", p.getDisplayName(),
                        "description", p.getDescription()
                ))
                .collect(Collectors.toList());
    }

    @Transactional
    public Integration createIntegration(String tenantId, String userId, Integration integration) {
        integration.setTenantId(tenantId);
        integration.setCreatedBy(userId);
        Integration saved = integrationRepository.save(integration);
        log.info("Created integration '{}' of type '{}' for tenant '{}'",
                saved.getName(), saved.getType(), tenantId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Integration> listIntegrations(String tenantId) {
        return integrationRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Integration getIntegration(String tenantId, String integrationId) {
        return integrationRepository.findByIdAndTenantId(integrationId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("Integration", integrationId));
    }

    @Transactional
    public boolean testConnection(String tenantId, String integrationId) {
        Integration integration = getIntegration(tenantId, integrationId);
        IntegrationPlugin plugin = findPlugin(integration.getType().name());

        try {
            Map<String, Object> config = parseJson(integration.getConfig());
            Map<String, Object> credentials = parseJson(integration.getCredentials());
            boolean result = plugin.testConnection(config, credentials);

            integration.setStatus(result ? Integration.IntegrationStatus.ACTIVE : Integration.IntegrationStatus.ERROR);
            integrationRepository.save(integration);

            return result;
        } catch (Exception e) {
            log.error("Connection test failed for integration '{}': {}", integrationId, e.getMessage());
            integration.setStatus(Integration.IntegrationStatus.ERROR);
            integrationRepository.save(integration);
            return false;
        }
    }

    @Transactional
    public Map<String, Object> executeIntegration(String tenantId, String integrationId, Map<String, Object> payload) {
        Integration integration = getIntegration(tenantId, integrationId);

        if (integration.getStatus() != Integration.IntegrationStatus.ACTIVE) {
            throw ServiceException.badRequest("Integration is not active");
        }

        IntegrationPlugin plugin = findPlugin(integration.getType().name());
        Map<String, Object> config = parseJson(integration.getConfig());
        Map<String, Object> credentials = parseJson(integration.getCredentials());

        return plugin.execute(config, credentials, payload);
    }

    private IntegrationPlugin findPlugin(String type) {
        return plugins.stream()
                .filter(p -> p.getPluginType().equals(type))
                .findFirst()
                .orElseThrow(() -> ServiceException.badRequest("No plugin found for type: " + type));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
