package com.aidriven.contracts.plugin;

import com.aidriven.contracts.ai.AiModelOperations;
import com.aidriven.contracts.source.SourceControlOperations;
import com.aidriven.contracts.tracker.IssueTrackerOperations;
import com.aidriven.contracts.tool.ToolProviderContract;

/**
 * Registry where plugins register their implementations during initialization.
 * <p>
 * The core system provides the implementation of this interface.
 * Plugins receive it via {@link PluginDescriptor#initialize(PluginRegistry)}.
 * </p>
 */
public interface PluginRegistry {

    /**
     * Registers a source control implementation.
     *
     * @param platformId Unique platform identifier (e.g., "gitlab", "azure-devops")
     * @param factory    Factory that creates client instances from credentials
     */
    void registerSourceControl(String platformId, SourceControlFactory factory);

    /**
     * Registers an issue tracker implementation.
     *
     * @param platformId Unique platform identifier (e.g., "linear", "notion")
     * @param factory    Factory that creates client instances from credentials
     */
    void registerIssueTracker(String platformId, IssueTrackerFactory factory);

    /**
     * Registers an AI model provider.
     *
     * @param providerId Unique provider identifier (e.g., "openai", "bedrock")
     * @param factory    Factory that creates client instances from credentials
     */
    void registerAiModel(String providerId, AiModelFactory factory);

    /**
     * Registers a tool provider for agent mode.
     *
     * @param provider The tool provider instance
     */
    void registerToolProvider(ToolProviderContract provider);

    // --- Factory interfaces ---

    @FunctionalInterface
    interface SourceControlFactory {
        SourceControlOperations create(java.util.Map<String, String> credentials, java.util.Map<String, String> config);
    }

    @FunctionalInterface
    interface IssueTrackerFactory {
        IssueTrackerOperations create(java.util.Map<String, String> credentials, java.util.Map<String, String> config);
    }

    @FunctionalInterface
    interface AiModelFactory {
        AiModelOperations create(java.util.Map<String, String> credentials, java.util.Map<String, String> config);
    }
}
