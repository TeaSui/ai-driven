package com.aidriven.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantResolverTest {

    private TenantRegistry registry;
    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
        registry.register(TenantConfig.builder().tenantId("acme").tenantName("ACME").active(true).build());
        registry.register(TenantConfig.builder().tenantId("startup").tenantName("Startup").active(true).build());
        registry.register(TenantConfig.builder().tenantId("inactive").tenantName("Inactive").active(false).build());
        resolver = new TenantResolver(registry, "acme");
    }

    @Test
    void resolve_from_header() {
        Map<String, Object> event = Map.of(
                "headers", Map.of("X-Tenant-Id", "startup"));
        TenantConfig result = resolver.resolve(event);
        assertEquals("startup", result.getTenantId());
    }

    @Test
    void resolve_from_lowercase_header() {
        Map<String, Object> event = Map.of(
                "headers", Map.of("x-tenant-id", "startup"));
        TenantConfig result = resolver.resolve(event);
        assertEquals("startup", result.getTenantId());
    }

    @Test
    void resolve_from_query_param() {
        Map<String, Object> event = Map.of(
                "queryStringParameters", Map.of("tenantId", "startup"));
        TenantConfig result = resolver.resolve(event);
        assertEquals("startup", result.getTenantId());
    }

    @Test
    void resolve_from_path_param() {
        Map<String, Object> event = Map.of(
                "pathParameters", Map.of("tenantId", "startup"));
        TenantConfig result = resolver.resolve(event);
        assertEquals("startup", result.getTenantId());
    }

    @Test
    void resolve_falls_back_to_default() {
        Map<String, Object> event = Map.of();
        TenantConfig result = resolver.resolve(event);
        assertEquals("acme", result.getTenantId());
    }

    @Test
    void resolve_throws_for_unknown_tenant() {
        Map<String, Object> event = Map.of(
                "headers", Map.of("X-Tenant-Id", "unknown-tenant"));
        assertThrows(TenantResolver.TenantNotFoundException.class,
                () -> resolver.resolve(event));
    }

    @Test
    void resolve_throws_for_inactive_tenant() {
        Map<String, Object> event = Map.of(
                "headers", Map.of("X-Tenant-Id", "inactive"));
        assertThrows(TenantResolver.TenantNotFoundException.class,
                () -> resolver.resolve(event));
    }

    @Test
    void resolve_throws_when_no_default_and_no_tenant() {
        TenantResolver noDefaultResolver = new TenantResolver(registry, null);
        assertThrows(TenantResolver.TenantNotFoundException.class,
                () -> noDefaultResolver.resolve(Map.of()));
    }

    @Test
    void resolveFromProjectKey_finds_matching_tenant() {
        registry.register(TenantConfig.builder()
                .tenantId("project-tenant")
                .active(true)
                .metadata(Map.of("jiraProjectKey", "ACME"))
                .build());

        var result = resolver.resolveFromProjectKey("ACME");
        assertTrue(result.isPresent());
        assertEquals("project-tenant", result.get().getTenantId());
    }

    @Test
    void resolveFromProjectKey_returns_empty_for_unknown() {
        var result = resolver.resolveFromProjectKey("UNKNOWN");
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveFromProjectKey_returns_empty_for_null() {
        var result = resolver.resolveFromProjectKey(null);
        assertTrue(result.isEmpty());
    }
}
