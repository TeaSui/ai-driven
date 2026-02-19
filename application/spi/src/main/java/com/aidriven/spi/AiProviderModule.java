package com.aidriven.spi;

import com.aidriven.core.agent.AiClient;

/**
 * SPI interface for AI provider modules (Claude, OpenAI, Bedrock, etc.).
 * Extends {@link ServiceModule} with typed access to the AI client.
 */
public interface AiProviderModule extends ServiceModule {

    @Override
    default ModuleCategory category() {
        return ModuleCategory.AI_PROVIDER;
    }

    /**
     * Returns the initialized AI client.
     *
     * @throws IllegalStateException if the module is not initialized
     */
    AiClient getClient();

    /**
     * Returns the model identifier currently configured.
     */
    String getModel();
}
