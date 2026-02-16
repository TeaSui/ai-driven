package com.aidriven.spi;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface for pluggable modules.
 *
 * <p>Each module that wants to participate in the AI-Driven platform
 * implements this interface and registers via {@code META-INF/services}.
 * The platform discovers modules at startup and wires them together
 * based on tenant configuration.</p>
 *
 * <p>This enables multi-tenant SaaS deployment where each tenant
 * can have a different combination of modules active.</p>
 *
 * @see ModuleRegistry
 * @see TenantContext
 */
public interface ServiceModule {

    /**
     * Unique identifier for this module (e.g., "jira", "bitbucket", "github", "claude").
     * Used in tenant configuration to enable/disable modules.
     */
    String moduleId();

    /**
     * Human-readable name for display purposes.
     */
    String displayName();

    /**
     * Module version string.
     */
    default String version() {
        return "1.0.0";
    }

    /**
     * List of module IDs that this module depends on.
     * The registry ensures dependencies are initialized first.
     */
    default List<String> dependencies() {
        return List.of();
    }

    /**
     * Initializes the module with the given configuration.
     * Called once during startup or tenant provisioning.
     *
     * @param config Module-specific configuration key-value pairs
     * @throws ModuleInitializationException if initialization fails
     */
    void initialize(Map<String, String> config) throws ModuleInitializationException;

    /**
     * Health check for the module.
     *
     * @return true if the module is healthy and operational
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * Gracefully shuts down the module, releasing any resources.
     */
    default void shutdown() {
        // Default no-op
    }
}
