package com.aidriven.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void get_withoutSet_returnsDefaultTenant() {
        assertEquals(TenantContext.DEFAULT_TENANT, TenantContext.get());
    }

    @Test
    void set_andGet_returnsSameTenantId() {
        TenantContext.set("acme-corp");
        assertEquals("acme-corp", TenantContext.get());
    }

    @Test
    void set_withNull_returnsDefaultTenant() {
        TenantContext.set(null);
        assertEquals(TenantContext.DEFAULT_TENANT, TenantContext.get());
    }

    @Test
    void clear_removesContext() {
        TenantContext.set("acme-corp");
        TenantContext.clear();
        assertEquals(TenantContext.DEFAULT_TENANT, TenantContext.get());
    }

    @Test
    void isSet_withoutSet_returnsFalse() {
        assertFalse(TenantContext.isSet());
    }

    @Test
    void isSet_afterSet_returnsTrue() {
        TenantContext.set("acme-corp");
        assertTrue(TenantContext.isSet());
    }

    @Test
    void isSet_afterClear_returnsFalse() {
        TenantContext.set("acme-corp");
        TenantContext.clear();
        assertFalse(TenantContext.isSet());
    }
}
