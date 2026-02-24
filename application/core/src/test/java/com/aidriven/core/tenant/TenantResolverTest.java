package com.aidriven.core.tenant;

import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantResolverTest {

    private TenantRegistry registry;
    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();

        registry.register(TenantConfig.builder()
                .tenantId("acme-corp")
                .tenantName("ACME")
                .active(true)
                .build());

        registry.register(TenantConfig.builder()
                .tenantId("startup-xyz")
                .tenantName("Startup XYZ")
                .active(true)
                .build());

        registry.register(TenantConfig.builder()
                .tenantId("inactive-corp")
                .tenantName("Inactive")
                .active(false)
                .build());

        registry.register(TenantConfig.builder()
                .tenantId("default")
                .tenantName("Default")
                .active(true)
                .build());

        resolver = new TenantResolver(registry, "default");
    }

    @Test
    void should_resolve_from_tenant_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .labels(List.of("ai-generate", "tenant:acme-corp"))
                .build();

        TenantConfig resolved = resolver.resolve(ticket);
        assertEquals("acme-corp", resolved.getTenantId());
    }

    @Test
    void should_resolve_case_insensitive_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .labels(List.of("TENANT:ACME-CORP"))
                .build();

        TenantConfig resolved = resolver.resolve(ticket);
        assertEquals("acme-corp", resolved.getTenantId());
    }

    @Test
    void should_skip_inactive_tenant_from_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .labels(List.of("tenant:inactive-corp"))
                .build();

        // Falls back to default since inactive-corp is not active
        TenantConfig resolved = resolver.resolve(ticket);
        assertEquals("default", resolved.getTenantId());
    }

    @Test
    void should_resolve_from_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-CORP-123")
                .projectKey("acme-corp")
                .labels(List.of("ai-generate"))
                .build();

        TenantConfig resolved = resolver.resolve(ticket);
        assertEquals("acme-corp", resolved.getTenantId());
    }

    @Test
    void should_fall_back_to_default_tenant() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("UNKNOWN-1")
                .projectKey("UNKNOWN")
                .labels(List.of("ai-generate"))
                .build();

        TenantConfig resolved = resolver.resolve(ticket);
        assertEquals("default", resolved.getTenantId());
    }

    @Test
    void should_prefer_label_over_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-CORP-1")
                .projectKey("acme-corp")
                .labels(List.of("tenant:startup-xyz"))
                .build();

        TenantConfig resolved = resolver.resolve(ticket);
        assertEquals("startup-xyz", resolved.getTenantId());
    }

    @Test
    void should_throw_when_no_tenant_and_no_default() {
        TenantResolver noDefaultResolver = new TenantResolver(registry, null);
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("UNKNOWN-1")
                .projectKey("UNKNOWN")
                .labels(List.of())
                .build();

        assertThrows(TenantRegistry.TenantNotFoundException.class,
                () -> noDefaultResolver.resolve(ticket));
    }
}
