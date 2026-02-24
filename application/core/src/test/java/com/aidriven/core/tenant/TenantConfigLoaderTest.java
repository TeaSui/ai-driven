package com.aidriven.core.tenant;

import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantConfigLoaderTest {

    private TenantConfigLoader loader;
    private TenantRegistry registry;
    private SecretsService secretsService;

    @BeforeEach
    void setUp() {
        secretsService = mock(SecretsService.class);
        loader = new TenantConfigLoader(new ObjectMapper(), secretsService);
        registry = new TenantRegistry();
    }

    @Test
    void loadFromJson_parses_single_tenant() {
        String json = """
                [
                  {
                    "tenantId": "acme",
                    "tenantName": "Acme Corp",
                    "platform": "GITHUB",
                    "defaultRepoOwner": "acme-org",
                    "defaultRepo": "backend",
                    "enabledPlugins": ["monitoring"],
                    "agentEnabled": true,
                    "active": true
                  }
                ]
                """;

        loader.loadFromJson(json, registry);

        assertEquals(1, registry.size());
        TenantConfig config = registry.getTenant("acme").orElseThrow();
        assertEquals("Acme Corp", config.getTenantName());
        assertEquals("GITHUB", config.getPlatform());
        assertEquals("acme-org", config.getDefaultRepoOwner());
        assertEquals("backend", config.getDefaultRepo());
        assertTrue(config.getEnabledPlugins().contains("monitoring"));
        assertTrue(config.isAgentEnabled());
        assertTrue(config.isActive());
    }

    @Test
    void loadFromJson_parses_multiple_tenants() {
        String json = """
                [
                  {"tenantId": "t1", "tenantName": "Tenant 1"},
                  {"tenantId": "t2", "tenantName": "Tenant 2"}
                ]
                """;

        loader.loadFromJson(json, registry);

        assertEquals(2, registry.size());
        assertTrue(registry.isRegistered("t1"));
        assertTrue(registry.isRegistered("t2"));
    }

    @Test
    void loadFromJson_invalid_json_does_not_throw() {
        assertDoesNotThrow(() -> loader.loadFromJson("not valid json", registry));
        assertEquals(0, registry.size());
    }

    @Test
    void loadFromJson_empty_array_registers_nothing() {
        loader.loadFromJson("[]", registry);
        assertEquals(0, registry.size());
    }

    @Test
    void loadFromJson_defaults_applied_when_fields_missing() {
        String json = "[{\"tenantId\": \"minimal\", \"tenantName\": \"Minimal\"}]";
        loader.loadFromJson(json, registry);

        TenantConfig config = registry.getTenant("minimal").orElseThrow();
        assertEquals("ai-generate", config.getTriggerLabel());
        assertEquals("@ai", config.getAgentTriggerPrefix());
        assertEquals(10, config.getAgentMaxTurns());
        assertEquals(200_000, config.getTokenBudgetPerTicket());
        assertTrue(config.isGuardrailsEnabled());
        assertFalse(config.isAgentEnabled());
        assertTrue(config.isActive());
        assertEquals("ai/", config.getBranchPrefix());
    }

    @Test
    void loadFromJson_skips_invalid_entry_continues_with_rest() {
        // First entry has no tenantId (will fail), second is valid
        String json = """
                [
                  {"tenantName": "No ID"},
                  {"tenantId": "valid", "tenantName": "Valid Tenant"}
                ]
                """;

        loader.loadFromJson(json, registry);

        // Only the valid one should be registered
        assertEquals(1, registry.size());
        assertTrue(registry.isRegistered("valid"));
    }
}
