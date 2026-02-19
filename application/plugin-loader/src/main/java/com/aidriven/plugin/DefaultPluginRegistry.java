package com.aidriven.plugin;

import com.aidriven.contracts.ai.AiModelOperations;
import com.aidriven.contracts.plugin.PluginRegistry;
import com.aidriven.contracts.source.SourceControlOperations;
import com.aidriven.contracts.tracker.IssueTrackerOperations;
import com.aidriven.contracts.tool.ToolProviderContract;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link PluginRegistry}.
 * <p>
 * Stores registered factories and providers in thread-safe maps.
 * Used by {@link PluginLoader} during plugin initialization.
 * </p>
 */
@Slf4j
public class DefaultPluginRegistry implements PluginRegistry {

    private final Map<String, SourceControlFactory> sourceControlFactories = new ConcurrentHashMap<>();
    private final Map<String, IssueTrackerFactory> issueTrackerFactories = new ConcurrentHashMap<>();
    private final Map<String, AiModelFactory> aiModelFactories = new ConcurrentHashMap<>();
    private final Map<String, ToolProviderContract> toolProviders = new ConcurrentHashMap<>();

    @Override
    public void registerSourceControl(String platformId, SourceControlFactory factory) {
        Objects.requireNonNull(platformId, "platformId must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        sourceControlFactories.put(platformId.toLowerCase(), factory);
        log.info("Registered source control: {}", platformId);
    }

    @Override
    public void registerIssueTracker(String platformId, IssueTrackerFactory factory) {
        Objects.requireNonNull(platformId, "platformId must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        issueTrackerFactories.put(platformId.toLowerCase(), factory);
        log.info("Registered issue tracker: {}", platformId);
    }

    @Override
    public void registerAiModel(String providerId, AiModelFactory factory) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        aiModelFactories.put(providerId.toLowerCase(), factory);
        log.info("Registered AI model: {}", providerId);
    }

    @Override
    public void registerToolProvider(ToolProviderContract provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        toolProviders.put(provider.namespace(), provider);
        log.info("Registered tool provider: {} ({} tools)",
                provider.namespace(), provider.toolDefinitions().size());
    }

    // --- Lookup methods ---

    public Optional<SourceControlFactory> getSourceControlFactory(String platformId) {
        return Optional.ofNullable(sourceControlFactories.get(platformId.toLowerCase()));
    }

    public Optional<IssueTrackerFactory> getIssueTrackerFactory(String platformId) {
        return Optional.ofNullable(issueTrackerFactories.get(platformId.toLowerCase()));
    }

    public Optional<AiModelFactory> getAiModelFactory(String providerId) {
        return Optional.ofNullable(aiModelFactories.get(providerId.toLowerCase()));
    }

    public Optional<ToolProviderContract> getToolProvider(String namespace) {
        return Optional.ofNullable(toolProviders.get(namespace));
    }

    public Collection<ToolProviderContract> getAllToolProviders() {
        return Collections.unmodifiableCollection(toolProviders.values());
    }

    public Set<String> getRegisteredSourceControlPlatforms() {
        return Collections.unmodifiableSet(sourceControlFactories.keySet());
    }

    public Set<String> getRegisteredIssueTrackerPlatforms() {
        return Collections.unmodifiableSet(issueTrackerFactories.keySet());
    }

    public Set<String> getRegisteredAiModelProviders() {
        return Collections.unmodifiableSet(aiModelFactories.keySet());
    }

    public Set<String> getRegisteredToolNamespaces() {
        return Collections.unmodifiableSet(toolProviders.keySet());
    }
}
