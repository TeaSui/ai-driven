package com.aidriven.core.spi;

import com.aidriven.core.source.SourceControlClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TenantAwareProviderResolverTest {

    private ServiceProviderRegistry registry;
    private TenantAwareProviderResolver resolver;
    private SourceControlClient bbClient;
    private SourceControlClient ghClient;

    @BeforeEach
    void setUp() {
        registry = new ServiceProviderRegistry();
        resolver = new TenantAwareProviderResolver(registry);

        bbClient = mock(SourceControlClient.class);
        ghClient = mock(SourceControlClient.class);

        registry.registerDefault(SourceControlClient.class, "bitbucket", bbClient);
        registry.register(SourceControlClient.class, "github", ghClient);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void should_resolve_from_tenant_preference() {
        TenantContext.set(TenantContext.of("acme", Map.of("source_control", "github")));

        SourceControlClient result = resolver.resolve(SourceControlClient.class, "source_control");

        assertSame(ghClient, result);
    }

    @Test
    void should_fallback_to_default_when_no_tenant_preference() {
        TenantContext.set(TenantContext.of("acme", Map.of()));

        SourceControlClient result = resolver.resolve(SourceControlClient.class, "source_control");

        assertSame(bbClient, result); // bitbucket is the default
    }

    @Test
    void should_fallback_to_default_when_preference_not_registered() {
        TenantContext.set(TenantContext.of("acme", Map.of("source_control", "gitlab")));

        SourceControlClient result = resolver.resolve(SourceControlClient.class, "source_control");

        assertSame(bbClient, result); // gitlab not registered, falls back to default
    }

    @Test
    void should_prefer_explicit_override_over_tenant_preference() {
        TenantContext.set(TenantContext.of("acme", Map.of("source_control", "bitbucket")));

        SourceControlClient result = resolver.resolve(
                SourceControlClient.class, "source_control", "github");

        assertSame(ghClient, result); // explicit override wins
    }

    @Test
    void should_ignore_null_override() {
        TenantContext.set(TenantContext.of("acme", Map.of("source_control", "github")));

        SourceControlClient result = resolver.resolve(
                SourceControlClient.class, "source_control", null);

        assertSame(ghClient, result); // falls through to tenant preference
    }

    @Test
    void should_ignore_blank_override() {
        TenantContext.set(TenantContext.of("acme", Map.of("source_control", "github")));

        SourceControlClient result = resolver.resolve(
                SourceControlClient.class, "source_control", "  ");

        assertSame(ghClient, result);
    }

    @Test
    void should_ignore_unregistered_override() {
        TenantContext.set(TenantContext.of("acme", Map.of("source_control", "github")));

        SourceControlClient result = resolver.resolve(
                SourceControlClient.class, "source_control", "gitlab");

        assertSame(ghClient, result); // gitlab not registered, falls to tenant pref
    }

    @Test
    void should_use_default_context_when_no_tenant_set() {
        // No TenantContext.set() called
        SourceControlClient result = resolver.resolve(SourceControlClient.class, "source_control");

        assertSame(bbClient, result); // default context has no preferences → registry default
    }
}
