package com.aidriven.core.tenant;

import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MultiTenantWorkflowRouterTest {

    private TenantRegistry registry;
    private MultiTenantWorkflowRouter router;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();

        registry.register(TenantConfig.builder()
                .tenantId("acme-corp")
                .tenantName("ACME Corporation")
                .defaultPlatform("GITHUB")
                .build());

        registry.register(TenantConfig.builder()
                .tenantId("startup-xyz")
                .tenantName("Startup XYZ")
                .defaultPlatform("BITBUCKET")
                .build());

        router = new MultiTenantWorkflowRouter(registry, "acme-corp");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void resolve_from_tenant_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("ai-generate", "tenant:startup-xyz"))
                .build();

        TenantConfig resolved = router.resolve(ticket);
        assertEquals("startup-xyz", resolved.getTenantId());
    }

    @Test
    void resolve_falls_back_to_default_tenant() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("ai-generate"))
                .build();

        TenantConfig resolved = router.resolve(ticket);
        assertEquals("acme-corp", resolved.getTenantId());
    }

    @Test
    void resolve_and_set_context_sets_thread_local() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("tenant:startup-xyz"))
                .build();

        router.resolveAndSetContext(ticket);

        assertTrue(TenantContext.isSet());
        assertEquals("startup-xyz", TenantContext.getTenantId());
    }

    @Test
    void resolve_throws_when_no_tenant_resolvable() {
        TenantRegistry emptyRegistry = new TenantRegistry();
        emptyRegistry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        emptyRegistry.register(TenantConfig.builder().tenantId("t2").tenantName("T2").build());
        MultiTenantWorkflowRouter strictRouter = new MultiTenantWorkflowRouter(emptyRegistry, null);

        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("ai-generate"))
                .build();

        assertThrows(TenantRegistry.TenantNotFoundException.class,
                () -> strictRouter.resolve(ticket));
    }

    @Test
    void resolve_uses_single_tenant_when_only_one_registered() {
        TenantRegistry singleRegistry = new TenantRegistry();
        singleRegistry.register(TenantConfig.builder()
                .tenantId("only-tenant")
                .tenantName("Only Tenant")
                .build());
        MultiTenantWorkflowRouter singleRouter = new MultiTenantWorkflowRouter(singleRegistry, null);

        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("ai-generate"))
                .build();

        TenantConfig resolved = singleRouter.resolve(ticket);
        assertEquals("only-tenant", resolved.getTenantId());
    }

    @Test
    void resolve_ignores_unknown_tenant_label() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .projectKey("PROJ")
                .labels(List.of("tenant:nonexistent"))
                .build();

        // Falls back to default
        TenantConfig resolved = router.resolve(ticket);
        assertEquals("acme-corp", resolved.getTenantId());
    }
}
