package com.aidriven.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {

    private ProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProviderRegistry();
    }

    // --- Stub implementations for testing ---

    interface TestProvider {
        String id();
    }

    record StubProviderA(String id) implements TestProvider {}
    record StubProviderB(String id) implements TestProvider {}

    // --- Registration ---

    @Nested
    class Registration {

        @Test
        void should_register_provider() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));

            assertTrue(registry.hasProvider(TestProvider.class, "a"));
            assertEquals(Set.of("a"), registry.getRegisteredProviders(TestProvider.class));
        }

        @Test
        void should_register_multiple_providers() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(TestProvider.class, "b", new StubProviderB("b"));

            assertEquals(2, registry.getRegisteredProviders(TestProvider.class).size());
            assertEquals(2, registry.totalProviderCount());
        }

        @Test
        void should_throw_on_null_interface() {
            assertThrows(NullPointerException.class,
                    () -> registry.register(null, "a", new StubProviderA("a")));
        }

        @Test
        void should_throw_on_null_provider_id() {
            assertThrows(NullPointerException.class,
                    () -> registry.register(TestProvider.class, null, new StubProviderA("a")));
        }

        @Test
        void should_throw_on_null_provider() {
            assertThrows(NullPointerException.class,
                    () -> registry.register(TestProvider.class, "a", null));
        }

        @Test
        void should_return_empty_set_for_unregistered_interface() {
            assertEquals(Set.of(), registry.getRegisteredProviders(TestProvider.class));
        }
    }

    // --- Resolution ---

    @Nested
    class Resolution {

        @Test
        void should_resolve_first_registered_when_no_bindings() {
            StubProviderA providerA = new StubProviderA("a");
            registry.register(TestProvider.class, "a", providerA);

            TestProvider resolved = registry.resolve(TestProvider.class);

            assertEquals("a", resolved.id());
        }

        @Test
        void should_resolve_default_binding() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(TestProvider.class, "b", new StubProviderB("b"));
            registry.setDefault(TestProvider.class, "b");

            TestProvider resolved = registry.resolve(TestProvider.class);

            assertEquals("b", resolved.id());
        }

        @Test
        void should_resolve_tenant_binding_over_default() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(TestProvider.class, "b", new StubProviderB("b"));
            registry.setDefault(TestProvider.class, "a");
            registry.bindTenant("acme", TestProvider.class, "b");

            TenantContext acme = TenantContext.of("acme", "Acme", Map.of());
            TestProvider resolved = registry.resolve(TestProvider.class, acme);

            assertEquals("b", resolved.id());
        }

        @Test
        void should_resolve_from_tenant_config() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(TestProvider.class, "b", new StubProviderB("b"));

            // Tenant config key is interface simple name lowercase
            TenantContext tenant = TenantContext.of("t1", "Tenant 1",
                    Map.of("testprovider", "b"));

            TestProvider resolved = registry.resolve(TestProvider.class, tenant);

            assertEquals("b", resolved.id());
        }

        @Test
        void should_fallback_to_default_when_tenant_has_no_binding() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(TestProvider.class, "b", new StubProviderB("b"));
            registry.setDefault(TestProvider.class, "b");

            TenantContext unknownTenant = TenantContext.of("unknown", "Unknown", Map.of());
            TestProvider resolved = registry.resolve(TestProvider.class, unknownTenant);

            assertEquals("b", resolved.id());
        }

        @Test
        void should_throw_when_no_providers_registered() {
            assertThrows(IllegalStateException.class,
                    () -> registry.resolve(TestProvider.class));
        }

        @Test
        void should_resolve_with_null_tenant_context() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));

            TestProvider resolved = registry.resolve(TestProvider.class, null);

            assertEquals("a", resolved.id());
        }
    }

    // --- Multi-interface ---

    @Nested
    class MultiInterface {

        interface OtherProvider {
            String name();
        }

        record StubOther(String name) implements OtherProvider {}

        @Test
        void should_isolate_providers_by_interface() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(OtherProvider.class, "x", new StubOther("x"));

            assertEquals(1, registry.getRegisteredProviders(TestProvider.class).size());
            assertEquals(1, registry.getRegisteredProviders(OtherProvider.class).size());
            assertFalse(registry.hasProvider(TestProvider.class, "x"));
            assertFalse(registry.hasProvider(OtherProvider.class, "a"));
        }

        @Test
        void should_bind_different_providers_per_tenant_per_interface() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(TestProvider.class, "b", new StubProviderB("b"));
            registry.register(OtherProvider.class, "x", new StubOther("x"));
            registry.register(OtherProvider.class, "y", new StubOther("y"));

            registry.bindTenant("t1", TestProvider.class, "b");
            registry.bindTenant("t1", OtherProvider.class, "y");

            TenantContext t1 = TenantContext.of("t1", "T1", Map.of());

            assertEquals("b", registry.resolve(TestProvider.class, t1).id());
            assertEquals("y", registry.resolve(OtherProvider.class, t1).name());
        }

        @Test
        void should_count_total_providers_across_interfaces() {
            registry.register(TestProvider.class, "a", new StubProviderA("a"));
            registry.register(TestProvider.class, "b", new StubProviderB("b"));
            registry.register(OtherProvider.class, "x", new StubOther("x"));

            assertEquals(3, registry.totalProviderCount());
        }
    }
}
