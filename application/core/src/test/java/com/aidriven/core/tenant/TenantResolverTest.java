package com.aidriven.core.tenant;

import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantResolverTest {

    @Test
    void resolve_from_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-123")
                .projectKey("ACME")
                .labels(List.of("ai-generate", "tenant:acme"))
                .build();

        String tenantId = TenantResolver.resolve(ticket, "default");
        assertEquals("acme", tenantId);
    }

    @Test
    void resolve_from_project_key_when_no_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("BETA-42")
                .projectKey("BETA")
                .labels(List.of("ai-generate"))
                .build();

        String tenantId = TenantResolver.resolve(ticket, "default");
        assertEquals("beta", tenantId);
    }

    @Test
    void resolve_falls_back_to_default_when_no_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey(null)
                .labels(List.of("ai-generate"))
                .build();

        String tenantId = TenantResolver.resolve(ticket, "default");
        assertEquals("default", tenantId);
    }

    @Test
    void resolve_null_ticket_returns_default() {
        assertEquals("default", TenantResolver.resolve(null, "default"));
    }

    @Test
    void resolveFromLabels_extracts_tenant_id() {
        String tenantId = TenantResolver.resolveFromLabels(List.of("backend", "tenant:my-company"));
        assertEquals("my-company", tenantId);
    }

    @Test
    void resolveFromLabels_returns_null_when_no_tenant_label() {
        assertNull(TenantResolver.resolveFromLabels(List.of("backend", "ai-generate")));
    }

    @Test
    void resolveFromLabels_returns_null_for_empty_list() {
        assertNull(TenantResolver.resolveFromLabels(List.of()));
    }

    @Test
    void resolveFromLabels_returns_null_for_null() {
        assertNull(TenantResolver.resolveFromLabels(null));
    }

    @Test
    void resolveFromLabels_is_case_insensitive() {
        String tenantId = TenantResolver.resolveFromLabels(List.of("TENANT:ACME"));
        assertEquals("acme", tenantId);
    }

    @Test
    void resolveFromProjectKey_lowercases_key() {
        assertEquals("acme", TenantResolver.resolveFromProjectKey("ACME"));
    }

    @Test
    void resolveFromProjectKey_returns_null_for_blank() {
        assertNull(TenantResolver.resolveFromProjectKey(""));
        assertNull(TenantResolver.resolveFromProjectKey(null));
    }

    @Test
    void label_takes_priority_over_project_key() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("ACME-1")
                .projectKey("ACME")
                .labels(List.of("tenant:override-tenant"))
                .build();

        // Label "override-tenant" should win over project key "acme"
        assertEquals("override-tenant", TenantResolver.resolve(ticket, "default"));
    }
}
