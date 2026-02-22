package com.teasui.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void setAndGetTenantId_shouldWorkCorrectly() {
        TenantContext.setTenantId("tenant-123");
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-123");
    }

    @Test
    void setAndGetUserId_shouldWorkCorrectly() {
        TenantContext.setUserId("user-456");
        assertThat(TenantContext.getUserId()).isEqualTo("user-456");
    }

    @Test
    void clear_shouldRemoveBothContextValues() {
        TenantContext.setTenantId("tenant-123");
        TenantContext.setUserId("user-456");
        TenantContext.clear();

        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
    }

    @Test
    void hasTenant_whenTenantSet_shouldReturnTrue() {
        TenantContext.setTenantId("tenant-123");
        assertThat(TenantContext.hasTenant()).isTrue();
    }

    @Test
    void hasTenant_whenNoTenant_shouldReturnFalse() {
        assertThat(TenantContext.hasTenant()).isFalse();
    }
}
