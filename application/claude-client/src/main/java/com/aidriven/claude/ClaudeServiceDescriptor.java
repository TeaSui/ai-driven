package com.aidriven.claude;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;

import java.util.Map;
import java.util.Set;

/**
 * Service descriptor for the Claude AI provider module.
 * Discovered via {@link java.util.ServiceLoader} when the claude-client
 * JAR is on the classpath.
 */
public class ClaudeServiceDescriptor implements ServiceDescriptor {

    @Override
    public String id() {
        return "claude";
    }

    @Override
    public String displayName() {
        return "Claude AI (Anthropic)";
    }

    @Override
    public ServiceCategory category() {
        return ServiceCategory.AI_PROVIDER;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of("claude.apiKey");
    }

    @Override
    public Map<String, String> optionalConfigDefaults() {
        return Map.of(
                "claude.model", "claude-opus-4-6",
                "claude.maxTokens", "32768",
                "claude.temperature", "0.2");
    }
}
