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

        TenantConfig acme = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme Corp")
                .jiraProjectKeys(List.of("ACME", "PROJ"))
                .build();
        TenantConfig startup = TenantConfig.builder()
                .tenantId("startup")
                .tenantName("Startup Inc")
                .jiraProjectKeys(List.of("START"))
                .build();

        registry.register(acme);
        registry.register(startup);

        resolver = new TenantResolver(registry, "acme");
    }

    @Test
    void resolve_by_explicit_tenant_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("tenant:startup", "ai-generate"))
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);

        assertTrue(result.isPresent());
        assertEquals("startup", result.get().getTenantId());
    }

    @Test
    void resolve_by_jira_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("START-42")
                .projectKey("START")
                .labels(List.of("ai-generate"))
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);

        assertTrue(result.isPresent());
        assertEquals("startup", result.get().getTenantId());
    }

    @Test
    void resolve_falls_back_to_default_tenant() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("UNKNOWN-1")
                .projectKey("UNKNOWN")
                .labels(List.of("ai-generate"))
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);

        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId()); // default
    }

    @Test
    void resolve_null_ticket_returns_default() {
        Optional<TenantConfig> result = resolver.resolve(null);
        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId());
    }

    @Test
    void resolve_label_takes_priority_over_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-1")
                .projectKey("ACME") // maps to acme
                .labels(List.of("tenant:startup")) // explicit override
                .build();

        Optional<TenantConfig> result = resolver.resolve(ticket);

        assertTrue(result.isPresent());
        assertEquals("startup", result.get().getTenantId());
    }

    @Test
    void resolveById_returns_correct_tenant() {
        Optional<TenantConfig> result = resolver.resolveById("startup");
        assertTrue(result.isPresent());
        assertEquals("Startup Inc", result.get().getTenantName());
    }

    @Test
    void resolveById_returns_empty_for_unknown() {
        assertTrue(resolver.resolveById("nonexistent").isEmpty());
    }
}
