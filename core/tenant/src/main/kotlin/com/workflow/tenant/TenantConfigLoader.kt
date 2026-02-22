package com.workflow.tenant

import com.workflow.api.TenantId

/**
 * Strategy interface for loading tenant configurations from various sources.
 * Implementations can load from YAML files, databases, AWS Parameter Store, etc.
 */
interface TenantConfigLoader {
    fun load(): List<TenantConfiguration>
    fun loadForTenant(tenantId: TenantId): TenantConfiguration?
}

/**
 * Composite loader that aggregates multiple config sources.
 */
class CompositeTenantConfigLoader(
    private val loaders: List<TenantConfigLoader>
) : TenantConfigLoader {

    override fun load(): List<TenantConfiguration> =
        loaders.flatMap { it.load() }
            .distinctBy { it.tenantId.value }

    override fun loadForTenant(tenantId: TenantId): TenantConfiguration? =
        loaders.firstNotNullOfOrNull { it.loadForTenant(tenantId) }
}

/**
 * Programmatic tenant config loader for testing and bootstrapping.
 */
class StaticTenantConfigLoader(
    private val configs: List<TenantConfiguration>
) : TenantConfigLoader {

    override fun load(): List<TenantConfiguration> = configs

    override fun loadForTenant(tenantId: TenantId): TenantConfiguration? =
        configs.find { it.tenantId == tenantId }
}
