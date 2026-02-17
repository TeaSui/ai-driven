package com.aidriven.platform;

import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Composes a tenant-specific workflow by assembling the appropriate
 * modules (tool providers, integrations) based on tenant configuration.
 *
 * <p>This is the central composition point that replaces hardcoded
 * wiring in ServiceFactory with dynamic, per-tenant assembly.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * WorkflowComposer composer = new WorkflowComposer(moduleRegistry, integrationFactory);
 * ToolRegistry registry = composer.composeToolRegistry(tenantContext, enabledModuleIds);
 * }</pre>
 */
@Slf4j
public class WorkflowComposer {

    private final ModuleRegistry moduleRegistry;
    private final List<ToolProviderFactory> toolProviderFactories;

    public WorkflowComposer(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = Objects.requireNonNull(moduleRegistry);
        this.toolProviderFactories = new ArrayList<>();
    }

    /**
     * Registers a factory that can create ToolProviders for a specific module.
     */
    public void registerToolProviderFactory(ToolProviderFactory factory) {
        toolProviderFactories.add(Objects.requireNonNull(factory));
    }

    /**
     * Composes a ToolRegistry for the given tenant with only the enabled modules.
     *
     * @param tenant          The tenant context
     * @param enabledModuleIds Module IDs the tenant has enabled
     * @return A ToolRegistry with only the tenant's enabled tool providers
     */
    public ToolRegistry composeToolRegistry(TenantContext tenant, List<String> enabledModuleIds) {
        ToolRegistry registry = new ToolRegistry();

        for (ToolProviderFactory factory : toolProviderFactories) {
            if (enabledModuleIds.contains(factory.moduleId())) {
                try {
                    ToolProvider provider = factory.create(tenant);
                    if (provider != null) {
                        registry.register(provider);
                        log.info("Composed tool provider '{}' for tenant '{}'",
                                provider.namespace(), tenant.tenantId());
                    }
                } catch (Exception e) {
                    log.error("Failed to create tool provider '{}' for tenant '{}': {}",
                            factory.moduleId(), tenant.tenantId(), e.getMessage(), e);
                }
            }
        }

        log.info("Composed {} tool providers for tenant '{}'",
                registry.getRegisteredNamespaces().size(), tenant.tenantId());
        return registry;
    }

    /**
     * Factory interface for creating tenant-specific ToolProvider instances.
     */
    public interface ToolProviderFactory {
        /** The module ID this factory handles. */
        String moduleId();

        /** Creates a ToolProvider configured for the given tenant. */
        ToolProvider create(TenantContext tenant);
    }
}
