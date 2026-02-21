package com.aidriven.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantResolverTest {

    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TenantResolver(Map.of(
                "ACME", "acme-corp",
                "BETA", "beta-inc"
        ));
    }

    // --- resolveFromEvent ---

    @Test
    void resolveFromEvent_withTenantHeader_returnsTenantId() {
        Map<String, Object> event = Map.of(
                "headers", Map.of("X-Tenant-Id", "acme-corp")
        );
        assertEquals("acme-corp", resolver.resolveFromEvent(event));
    }

    @Test
    void resolveFromEvent_withQueryParam_returnsTenantId() {
        Map<String, Object> event = Map.of(
                "queryStringParameters", Map.of("tenantId", "beta-inc")
        );
        assertEquals("beta-inc", resolver.resolveFromEvent(event));
    }

    @Test
    void resolveFromEvent_headerTakesPriorityOverQueryParam() {
        Map<String, Object> event = Map.of(
                "headers", Map.of("X-Tenant-Id", "from-header"),
                "queryStringParameters", Map.of("tenantId", "from-query")
        );
        assertEquals("from-header", resolver.resolveFromEvent(event));
    }

    @Test
    void resolveFromEvent_withNoTenantInfo_returnsDefault() {
        Map<String, Object> event = Map.of();
        assertEquals(TenantContext.DEFAULT_TENANT, resolver.resolveFromEvent(event));
    }

    @Test
    void resolveFromEvent_withEmptyHeaders_returnsDefault() {
        Map<String, Object> event = Map.of("headers", Map.of());
        assertEquals(TenantContext.DEFAULT_TENANT, resolver.resolveFromEvent(event));
    }

    // --- resolveFromTicketKey ---

    @Test
    void resolveFromTicketKey_withKnownProjectKey_returnsTenantId() {
        assertEquals("acme-corp", resolver.resolveFromTicketKey("ACME-123"));
        assertEquals("beta-inc", resolver.resolveFromTicketKey("BETA-456"));
    }

    @Test
    void resolveFromTicketKey_withUnknownProjectKey_returnsDefault() {
        assertEquals(TenantContext.DEFAULT_TENANT, resolver.resolveFromTicketKey("UNKNOWN-123"));
    }

    @Test
    void resolveFromTicketKey_withNullKey_returnsDefault() {
        assertEquals(TenantContext.DEFAULT_TENANT, resolver.resolveFromTicketKey(null));
    }

    @Test
    void resolveFromTicketKey_withMalformedKey_returnsDefault() {
        assertEquals(TenantContext.DEFAULT_TENANT, resolver.resolveFromTicketKey("NOHYPHEN"));
    }

    @Test
    void resolveFromTicketKey_withNoMappings_returnsDefault() {
        TenantResolver noMappingResolver = new TenantResolver();
        assertEquals(TenantContext.DEFAULT_TENANT, noMappingResolver.resolveFromTicketKey("ACME-123"));
    }
}
