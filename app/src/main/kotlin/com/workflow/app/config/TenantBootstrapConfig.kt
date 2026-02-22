package com.workflow.app.config

import com.workflow.api.TenantId
import com.workflow.tenant.*
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

/**
 * Bootstraps tenant configurations at application startup.
 * In production, this would load from a database or config service.
 */
@Configuration
class TenantBootstrapConfig(
    private val tenantRegistry: TenantRegistry,
    private val tenantConfigLoaders: List<TenantConfigLoader>
) {
    private val log = LoggerFactory.getLogger(TenantBootstrapConfig::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        log.info("Loading tenant configurations...")
        val loader = CompositeTenantConfigLoader(tenantConfigLoaders)
        val configs = loader.load()
        configs.forEach { config ->
            tenantRegistry.register(config)
            log.info("Loaded tenant: {} (plan={})", config.tenantId.value, config.plan)
        }
        log.info("Loaded {} tenant configurations", configs.size)
    }
}
