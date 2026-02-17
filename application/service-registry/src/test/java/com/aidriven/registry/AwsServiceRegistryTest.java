package com.aidriven.registry;

import com.aidriven.core.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsServiceRegistryTest {

    @Mock
    private AppConfig appConfig;

    @Test
    void constructor_withDefaultTenant_usesDefaultContext() {
        AwsServiceRegistry registry = new AwsServiceRegistry(appConfig);

        assertNotNull(registry.getTenantContext());
        assertTrue(registry.getTenantContext().isDefault());
    }

    @Test
    void constructor_withCustomTenant_usesProvidedContext() {
        TenantContext tenant = new TenantContext(
                "acme", "Acme Corp",
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of());

        AwsServiceRegistry registry = new AwsServiceRegistry(appConfig, tenant);

        assertEquals("acme", registry.getTenantContext().tenantId());
        assertFalse(registry.getTenantContext().isDefault());
    }

    @Test
    void getAppConfig_returnsProvidedConfig() {
        AwsServiceRegistry registry = new AwsServiceRegistry(appConfig);

        assertSame(appConfig, registry.getAppConfig());
    }

    @Test
    void getObjectMapper_returnsSameInstance() {
        AwsServiceRegistry registry = new AwsServiceRegistry(appConfig);

        var first = registry.getObjectMapper();
        var second = registry.getObjectMapper();

        assertSame(first, second);
    }

    @Test
    void getMcpToolProviders_returnsEmptyForNullConfig() {
        when(appConfig.getMcpServersConfig()).thenReturn(null);

        AwsServiceRegistry registry = new AwsServiceRegistry(appConfig);

        assertTrue(registry.getMcpToolProviders().isEmpty());
    }

    @Test
    void getMcpToolProviders_returnsEmptyForEmptyArrayConfig() {
        when(appConfig.getMcpServersConfig()).thenReturn("[]");

        AwsServiceRegistry registry = new AwsServiceRegistry(appConfig);

        assertTrue(registry.getMcpToolProviders().isEmpty());
    }
}
