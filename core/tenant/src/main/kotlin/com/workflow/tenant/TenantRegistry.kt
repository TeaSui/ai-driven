package com.workflow.tenant

import com.workflow.api.TenantId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for tenant configurations.
 * Supports dynamic registration and lookup of tenant configs.
 */
interface TenantRegistry {
    fun register(config: TenantConfiguration)
    fun get(tenantId: TenantId): TenantConfiguration
    fun findOrNull(tenantId: TenantId): TenantConfiguration?
    fun getAll(): List<TenantConfiguration>
    fun deregister(tenantId: TenantId)
    fun exists(tenantId: TenantId): Boolean
}

/**
 * In-memory implementation of TenantRegistry.
 * Suitable for single-node deployments; can be replaced with distributed cache.
 */
class InMemoryTenantRegistry : TenantRegistry {

    private val log = LoggerFactory.getLogger(InMemoryTenantRegistry::class.java)
    private val tenants = ConcurrentHashMap<String, TenantConfiguration>()

    override fun register(config: TenantConfiguration) {
        tenants[config.tenantId.value] = config
        log.info("Registered tenant: {} (plan={})", config.tenantId.value, config.plan)
    }

    override fun get(tenantId: TenantId): TenantConfiguration =
        findOrNull(tenantId) ?: throw TenantNotFoundException(tenantId)

    override fun findOrNull(tenantId: TenantId): TenantConfiguration? =
        tenants[tenantId.value]

    override fun getAll(): List<TenantConfiguration> = tenants.values.toList()

    override fun deregister(tenantId: TenantId) {
        tenants.remove(tenantId.value)
        log.info("Deregistered tenant: {}", tenantId.value)
    }

    override fun exists(tenantId: TenantId): Boolean = tenants.containsKey(tenantId.value)
}

class TenantNotFoundException(tenantId: TenantId) :
    RuntimeException("Tenant not found: ${tenantId.value}")
