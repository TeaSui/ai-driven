package com.aidriven.spi;

import java.util.Map;
import java.util.Set;

/**
 * Describes a pluggable service module that can be discovered and loaded at runtime.
 * Each module (source control, issue tracker, AI provider, etc.) publishes a descriptor
 * so the platform can compose the right set of integrations per tenant.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}.</p>
 */
public interface ServiceDescriptor {

    /**
     * Unique identifier for this service module (e.g., "bitbucket", "jira", "claude").
     */
    String id();

    /**
     * Human-readable display name.
     */
    String displayName();

    /**
     * The category this module belongs to.
     */
    ServiceCategory category();

    /**
     * Semantic version of this module.
     */
    String version();

    /**
     * Configuration keys required by this module.
     * Used for validation during tenant onboarding.
     *
     * @return Set of required configuration key names
     */
    Set<String> requiredConfigKeys();

    /**
     * Optional configuration keys with their default values.
     *
     * @return Map of optional key → default value
     */
    default Map<String, String> optionalConfigDefaults() {
        return Map.of();
    }

    /**
     * IDs of other modules this module depends on.
     * The platform ensures dependencies are loaded first.
     */
    default Set<String> dependencies() {
        return Set.of();
    }
}
