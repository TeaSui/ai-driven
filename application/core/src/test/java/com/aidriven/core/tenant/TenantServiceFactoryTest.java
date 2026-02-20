package com.aidriven.core.tenant;

import com.aidriven.spi.*;
import com.aidriven.spi.event.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantServiceFactoryTest {

    private ModuleRegistry registry;
    private EventBus eventBus;
    private TenantServiceFactory factory;

    @BeforeEach
    void setUp() {
        registry = new ModuleRegistry();
        eventBus = new EventBus();
        factory = new TenantServiceFactory(registry, eventBus);
    }

    @Test
    void should_create_tenant_services() {
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER, Set.of("jira.baseUrl")));

        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Acme")
                .config("jira.baseUrl", "https://acme.atlassian.net")
                .build();

        TenantServices services = factory.getOrCreate(ctx, Set.of("jira"));

        assertNotNull(services);
        assertEquals("t1", services.context().tenantId());
        assertTrue(services.hasModule("jira"));
        assertEquals(1, services.allModules().size());
    }

    @Test
    void should_cache_tenant_services() {
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER, Set.of()));

        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Acme")
                .build();

        TenantServices first = factory.getOrCreate(ctx, Set.of("jira"));
        TenantServices second = factory.getOrCreate(ctx, Set.of("jira"));

        assertSame(first, second);
        assertEquals(1, factory.cacheSize());
    }

    @Test
    void should_throw_for_invalid_config() {
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER, Set.of("jira.baseUrl")));

        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Acme")
                .build(); // Missing jira.baseUrl

        assertThrows(IllegalStateException.class, () ->
                factory.getOrCreate(ctx, Set.of("jira")));
    }

    @Test
    void should_evict_cached_services() {
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER, Set.of()));

        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Acme")
                .build();

        factory.getOrCreate(ctx, Set.of("jira"));
        assertEquals(1, factory.cacheSize());

        factory.evict("t1");
        assertEquals(0, factory.cacheSize());
    }

    @Test
    void should_isolate_tenants() {
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER, Set.of()));
        registry.register(createDescriptor("github", ServiceCategory.SOURCE_CONTROL, Set.of()));

        TenantContext ctx1 = TenantContext.builder().tenantId("t1").tenantName("Acme").build();
        TenantContext ctx2 = TenantContext.builder().tenantId("t2").tenantName("Beta").build();

        TenantServices s1 = factory.getOrCreate(ctx1, Set.of("jira"));
        TenantServices s2 = factory.getOrCreate(ctx2, Set.of("jira", "github"));

        assertEquals(1, s1.allModules().size());
        assertEquals(2, s2.allModules().size());
        assertTrue(s2.hasModule("github"));
        assertFalse(s1.hasModule("github"));
    }

    @Test
    void should_provide_modules_by_category() {
        registry.register(createDescriptor("bitbucket", ServiceCategory.SOURCE_CONTROL, Set.of()));
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER, Set.of()));

        TenantContext ctx = TenantContext.builder().tenantId("t1").tenantName("Test").build();
        TenantServices services = factory.getOrCreate(ctx, Set.of("bitbucket", "jira"));

        assertEquals(1, services.modulesForCategory(ServiceCategory.SOURCE_CONTROL).size());
        assertEquals(1, services.modulesForCategory(ServiceCategory.ISSUE_TRACKER).size());
        assertTrue(services.modulesForCategory(ServiceCategory.MONITORING).isEmpty());
    }

    @Test
    void should_return_primary_module() {
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER, Set.of()));

        TenantContext ctx = TenantContext.builder().tenantId("t1").tenantName("Test").build();
        TenantServices services = factory.getOrCreate(ctx, Set.of("jira"));

        assertTrue(services.primaryModule(ServiceCategory.ISSUE_TRACKER).isPresent());
        assertEquals("jira", services.primaryModule(ServiceCategory.ISSUE_TRACKER).get().id());
        assertTrue(services.primaryModule(ServiceCategory.MONITORING).isEmpty());
    }

    // --- Helper ---

    private ServiceDescriptor createDescriptor(String id, ServiceCategory category, Set<String> requiredKeys) {
        return new ServiceDescriptor() {
            public String id() { return id; }
            public String displayName() { return id + " module"; }
            public ServiceCategory category() { return category; }
            public String version() { return "1.0.0"; }
            public Set<String> requiredConfigKeys() { return requiredKeys; }
        };
    }
}
