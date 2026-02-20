package com.aidriven.spi;

import java.util.List;

/**
 * Describes a pluggable module that can be loaded into the system.
 * Each module declares what SPI interfaces it provides implementations for.
 *
 * <p>Modules are self-contained units that can be independently built,
 * tested, and deployed. A tenant's subscription determines which modules
 * are activated.</p>
 */
public interface ModuleDescriptor {

    /**
     * Unique module identifier (e.g., "bitbucket-integration", "jira-integration").
     */
    String moduleId();

    /**
     * Human-readable module name.
     */
    String displayName();

    /**
     * Semantic version of this module.
     */
    String version();

    /**
     * List of SPI interfaces this module provides implementations for.
     */
    List<Class<?>> providedInterfaces();

    /**
     * Registers this module's providers into the registry.
     * Called during application startup.
     *
     * @param registry The provider registry to register into
     * @param config   Module-specific configuration
     */
    void register(ProviderRegistry registry, java.util.Map<String, String> config);
}
