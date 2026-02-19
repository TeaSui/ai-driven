package com.aidriven.core.spi;

import java.util.List;
import java.util.Map;

/**
 * Describes a pluggable module that can be registered with the system.
 *
 * <p>Each integration module (jira-client, bitbucket-client, github-client,
 * claude-client, etc.) implements this interface to declare:
 * <ul>
 *   <li>Its identity (name, version)</li>
 *   <li>What service interfaces it provides</li>
 *   <li>What configuration keys it requires</li>
 *   <li>How to initialize and register its providers</li>
 * </ul>
 *
 * <p>Modules are discovered via Java SPI ({@code ServiceLoader}) or
 * registered programmatically. This enables:
 * <ul>
 *   <li>Compile-time independence between modules</li>
 *   <li>Per-tenant module selection (only load what's needed)</li>
 *   <li>Independent testing of each module</li>
 * </ul>
 *
 * <h3>Example implementation:</h3>
 * <pre>{@code
 * public class BitbucketModule implements ModuleDescriptor {
 *     public String name() { return "bitbucket"; }
 *     public String version() { return "1.0.0"; }
 *     public List<Class<?>> providedServices() {
 *         return List.of(SourceControlClient.class);
 *     }
 *     public void initialize(ServiceProviderRegistry registry, Map<String, String> config) {
 *         BitbucketClient client = new BitbucketClient(config.get("auth"), ...);
 *         registry.register(SourceControlClient.class, "bitbucket", client);
 *     }
 * }
 * }</pre>
 */
public interface ModuleDescriptor {

    /**
     * Unique module name (e.g., "bitbucket", "github", "jira", "claude").
     */
    String name();

    /**
     * Module version for compatibility tracking.
     */
    String version();

    /**
     * Service interfaces this module provides implementations for.
     * Used for dependency validation and documentation.
     */
    List<Class<?>> providedServices();

    /**
     * Configuration keys required by this module.
     * Used for validation before initialization.
     *
     * @return List of required config keys (e.g., ["SECRET_ARN", "WORKSPACE"])
     */
    default List<String> requiredConfigKeys() {
        return List.of();
    }

    /**
     * Optional configuration keys with their default values.
     */
    default Map<String, String> optionalConfigDefaults() {
        return Map.of();
    }

    /**
     * Initializes the module and registers its providers.
     *
     * @param registry The service provider registry to register with
     * @param config   Configuration map (from environment, secrets, etc.)
     */
    void initialize(ServiceProviderRegistry registry, Map<String, String> config);

    /**
     * Called when the module is being shut down.
     * Override to clean up resources (connections, threads, etc.).
     */
    default void shutdown() {
        // Default no-op
    }

    /**
     * Priority for initialization ordering.
     * Lower values initialize first. Default is 100.
     * Core modules (config, secrets) should use lower values.
     */
    default int priority() {
        return 100;
    }
}
