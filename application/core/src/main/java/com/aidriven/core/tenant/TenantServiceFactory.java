package com.aidriven.core.tenant;

import com.aidriven.spi.ModuleRegistry;
import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;
import com.aidriven.spi.TenantContext;
import com.aidriven.spi.event.EventBus;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and caches tenant-specific service instances.
 * Each tenant gets its own isolated set of configured services
 * based on their module selections and configuration.
 *
 * <p>Thread-safe. Designed for Lambda environments where multiple
 * tenants may be served by the same process.</p>
 */
@Slf4j
public class TenantServiceFactory {

    private final ModuleRegistry moduleRegistry;
    private final EventBus eventBus;
    private final Map<String, TenantServices> tenantCache = new ConcurrentHashMap<>();

    public TenantServiceFactory(ModuleRegistry moduleRegistry, EventBus eventBus) {
        this.moduleRegistry = Objects.requireNonNull(moduleRegistry);
        this.eventBus = Objects.requireNonNull(eventBus);
    }

    /**
     * Gets or creates the service set for a tenant.
     *
     * @param context Tenant context with configuration
     * @param moduleIds Set of module IDs the tenant has subscribed to
     * @return Configured services for the tenant
     */
    public TenantServices getOrCreate(TenantContext context, Set<String> moduleIds) {
        return tenantCache.computeIfAbsent(context.tenantId(), id -> {
            log.info("Creating services for tenant '{}' with modules: {}", id, moduleIds);

            // Validate configuration
            List<String> errors = moduleRegistry.validateTenantConfig(moduleIds, context.config());
            if (!errors.isEmpty()) {
                throw new IllegalStateException(
                        "Invalid tenant configuration for '" + id + "': " + String.join("; ", errors));
            }

            return new TenantServices(context, resolveDescriptors(moduleIds), eventBus);
        });
    }

    /**
     * Evicts a tenant's cached services, forcing re-creation on next access.
     * Useful when tenant configuration changes.
     */
    public void evict(String tenantId) {
        TenantServices removed = tenantCache.remove(tenantId);
        if (removed != null) {
            log.info("Evicted cached services for tenant '{}'", tenantId);
        }
    }

    /**
     * Returns the number of cached tenant service sets.
     */
    public int cacheSize() {
        return tenantCache.size();
    }

    private List<ServiceDescriptor> resolveDescriptors(Set<String> moduleIds) {
        List<ServiceDescriptor> descriptors = new ArrayList<>();
        for (String id : moduleIds) {
            moduleRegistry.findById(id).ifPresent(descriptors::add);
        }
        return descriptors;
    }
}
