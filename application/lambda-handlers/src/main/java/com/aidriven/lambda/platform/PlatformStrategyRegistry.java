package com.aidriven.lambda.platform;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry that maps platform identifiers to their {@link PlatformStrategy}
 * implementations.
 *
 * <p>Adding a new platform (GitLab, Linear, Azure DevOps, …) requires only
 * implementing {@link PlatformStrategy} and registering it here — no changes
 * to any handler are needed.
 */
public class PlatformStrategyRegistry {

    private final Map<String, PlatformStrategy> strategies = new HashMap<>();

    public PlatformStrategyRegistry register(PlatformStrategy strategy) {
        strategies.put(strategy.platform().toUpperCase(), strategy);
        return this;
    }

    /**
     * Retrieves the strategy for the given platform.
     *
     * @param platform platform identifier (e.g. "JIRA", "GITHUB") — case-insensitive
     * @return the registered strategy
     * @throws IllegalArgumentException if no strategy is registered for the platform
     */
    public PlatformStrategy get(String platform) {
        String key = platform != null ? platform.toUpperCase() : "";
        PlatformStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "No PlatformStrategy registered for platform: '" + platform + "'. "
                            + "Registered platforms: " + strategies.keySet());
        }
        return strategy;
    }

    /** Returns true if a strategy is registered for the given platform. */
    public boolean supports(String platform) {
        return platform != null && strategies.containsKey(platform.toUpperCase());
    }
}
