package com.aidriven.spi;

/**
 * SPI marker interface for AI engine modules (Claude, OpenAI, Bedrock, etc.).
 *
 * <p>Modules implementing this interface provide AI/LLM capabilities
 * and can be swapped per tenant.</p>
 */
public interface AiEngineModule extends ServiceModule {

    /**
     * Module category constant for AI engines.
     */
    String CATEGORY = "ai_engine";
}
