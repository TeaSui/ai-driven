package com.aidriven.spi.provider;

import com.aidriven.spi.model.OperationContext;
import java.util.List;
import java.util.Map;

/**
 * SPI for AI Chat Models (Claude, OpenAI, Bedrock).
 */
public interface AiProvider {
    /** Returns the unique identifier for this provider (e.g., "claude"). */
    String getName();

    /** Sends a chat request with tool definitions. */
    ChatResponse chat(OperationContext context, String systemPrompt, List<Map<String, Object>> messages,
            List<Map<String, Object>> tools);

    /** Simplified chat response container to avoid SDK coupling in SPI. */
    interface ChatResponse {
        String getText();

        List<Map<String, Object>> getToolCalls();

        int getInputTokens();

        int getOutputTokens();
    }
}
