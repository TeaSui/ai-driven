package com.workflow.tenant

import com.workflow.api.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TenantRegistryTest {

    private lateinit var registry: InMemoryTenantRegistry

    @BeforeEach
    fun setUp() {
        registry = InMemoryTenantRegistry()
    }

    @Test
    fun `should register and retrieve tenant`() {
        val config = buildTenantConfig("tenant-1")
        registry.register(config)

        val retrieved = registry.get(TenantId.of("tenant-1"))
        assertThat(retrieved).isEqualTo(config)
    }

    @Test
    fun `should throw when tenant not found`() {
        assertThatThrownBy {
            registry.get(TenantId.of("unknown"))
        }.isInstanceOf(TenantNotFoundException::class.java)
    }

    @Test
    fun `should return null for missing tenant with findOrNull`() {
        val result = registry.findOrNull(TenantId.of("missing"))
        assertThat(result).isNull()
    }

    @Test
    fun `should deregister tenant`() {
        val config = buildTenantConfig("tenant-2")
        registry.register(config)
        registry.deregister(TenantId.of("tenant-2"))

        assertThat(registry.exists(TenantId.of("tenant-2"))).isFalse()
    }

    @Test
    fun `should list all tenants`() {
        registry.register(buildTenantConfig("t1"))
        registry.register(buildTenantConfig("t2"))

        assertThat(registry.getAll()).hasSize(2)
    }

    @Test
    fun `tenant context should be thread-local`() {
        val tenantId = TenantId.of("ctx-tenant")
        TenantContext.withTenant(tenantId) {
            assertThat(TenantContext.get()).isEqualTo(tenantId)
        }
        assertThat(TenantContext.getOrNull()).isNull()
    }

    private fun buildTenantConfig(id: String) = TenantConfiguration(
        tenantId = TenantId.of(id),
        name = "Tenant $id",
        plan = TenantPlan.STARTER,
        enabledPlugins = setOf("email-plugin", "webhook-plugin")
    )
}
