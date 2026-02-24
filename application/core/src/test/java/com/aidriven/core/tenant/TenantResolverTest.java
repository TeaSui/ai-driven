package com.aidriven.core.tenant;

import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TenantResolverTest {

    private TenantRegistry registry;
    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
        registry.register(TenantConfig.builder().tenantId("acme").tenantName("Acme Corp").active(true).build());
        registry.register(TenantConfig.builder().tenantId("startup").tenantName("Startup XYZ").active(true).build());
        registry.register(TenantConfig.builder().tenantId("default").tenantName("Default").active(true).build());
        resolver = new TenantResolver(registry, "default");
    }

    @Test
    void resolve_from_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-1")
                .projectKey("ACME")
                .labels(List.of("ai-generate", "tenant:acme"))
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);
        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId());
    }

    @Test
    void resolve_from_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-1")
                .projectKey("acme")
                .labels(List.of("ai-generate"))
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);
        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId());
    }

    @Test
    void resolve_falls_back_to_default() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("UNKNOWN-1")
                .projectKey("UNKNOWN")
                .labels(List.of("ai-generate"))
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);
        assertTrue(result.isPresent());
        assertEquals("default", result.get().getTenantId());
    }

    @Test
    void resolve_null_ticket_returns_default() {
        Optional<TenantConfig> result = resolver.resolve(null);
        assertTrue(result.isPresent());
        assertEquals("default", result.get().getTenantId());
    }

    @Test
    void resolve_label_takes_priority_over_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-1")
                .projectKey("acme")
                .labels(List.of("tenant:startup"))
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);
        assertTrue(result.isPresent());
        assertEquals("startup", result.get().getTenantId());
    }

    @Test
    void resolve_unknown_tenant_label_falls_through() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("tenant:nonexistent"))
                .build();

        // Falls through to project key (no match), then default
        Optional<TenantConfig> result = resolver.resolve(ticket);
        assertTrue(result.isPresent());
        assertEquals("default", result.get().getTenantId());
    }

    @Test
    void resolveFromLabels_case_insensitive() {
        Optional<TenantConfig> result = resolver.resolveFromLabels(List.of("TENANT:ACME"));
        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId());
    }

    @Test
    void resolveFromLabels_null_returns_empty() {
        assertTrue(resolver.resolveFromLabels(null).isEmpty());
    }

    @Test
    void resolveFromLabels_empty_returns_empty() {
        assertTrue(resolver.resolveFromLabels(List.of()).isEmpty());
    }

    @Test
    void resolveFromProjectKey_case_insensitive() {
        Optional<TenantConfig> result = resolver.resolveFromProjectKey("ACME");
        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId());
    }

    @Test
    void resolveFromProjectKey_null_returns_empty() {
        assertTrue(resolver.resolveFromProjectKey(null).isEmpty());
    }
}
