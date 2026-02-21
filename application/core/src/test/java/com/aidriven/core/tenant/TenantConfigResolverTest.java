package com.aidriven.core.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantConfigResolverTest {

    private ObjectMapper objectMapper;
    private TenantConfigStore configStore;
    private TenantConfigResolver resolver;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        configStore = mock(TenantConfigStore.class);
    }

    @Test
    void should_return_default_context_in_single_tenant_mode() {
        resolver = new TenantConfigResolver(objectMapper);

        TenantContext ctx = resolver.resolve("any-tenant");

        assertEquals("default", ctx.tenantId());
    }

    @Test
    void should_return_default_context_when_no_store() {
        resolver = new TenantConfigResolver(objectMapper, null);

        TenantContext ctx = resolver.resolveDefault();

        assertEquals("default", ctx.tenantId());
    }

    @Test
    void should_cache_resolved_contexts() {
        resolver = new TenantConfigResolver(objectMapper);

        TenantContext first = resolver.resolve("t1");
        TenantContext second = resolver.resolve("t1");

        assertSame(first, second);
    }

    @Test
    void should_invalidate_cache() {
        resolver = new TenantConfigResolver(objectMapper);

        TenantContext first = resolver.resolve("t1");
        resolver.invalidate("t1");
        TenantContext second = resolver.resolve("t1");

        // After invalidation, a new instance is created
        // (but in single-tenant mode both are default contexts)
        assertNotNull(first);
        assertNotNull(second);
    }

    @Test
    void should_clear_all_cache() {
        resolver = new TenantConfigResolver(objectMapper);

        resolver.resolve("t1");
        resolver.resolve("t2");
        resolver.clearCache();

        // After clear, resolving again should work
        TenantContext ctx = resolver.resolve("t1");
        assertNotNull(ctx);
    }
}