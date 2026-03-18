package com.aidriven.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

/**
 * Platform-agnostic interface for AI model interactions with tool-use support.
 *
 * <p>This is the primary abstraction for communicating with AI language models.
 * The core module depends on this interface; concrete implementations live in
 * their own modules and are resolved at runtime via {@code ServiceFactory}.</p>
 *
 * <h3>Known Implementations</h3>
 * <ul>
 *   <li>{@code SpringAiClientAdapter} -- uses Spring AI's {@code AnthropicChatModel}
 *       and {@code AnthropicApi} (activated via {@code CLAUDE_PROVIDER=SPRING_AI})</li>
 *   <li>{@code BedrockClient} -- uses AWS Bedrock's Anthropic Claude runtime
 *       (activated via {@code CLAUDE_PROVIDER=BEDROCK})</li>
 * </ul>
 *
 * <h3>Immutable Builder Pattern</h3>
 * <p>Configuration methods ({@link #withModel}, {@link #withMaxTokens},
 * {@link #withTemperature}) return <em>new</em> instances rather than mutating
 * the receiver. This makes clients safe to share across threads and allows
 * per-request configuration overrides without side effects.</p>
 *
 * <h3>Architecture Note</h3>
 * <p>This follows the same pattern as {@link com.aidriven.core.source.SourceControlClient}
 * and {@link com.aidriven.core.tracker.IssueTrackerClient}: core defines interfaces,
 * concrete implementations live in separate modules.</p>
 *
 * @see com.aidriven.core.agent.tool.ToolProvider
 * @see com.aidriven.spi.provider.AiProvider
 * @since 1.0
 */
public interface AiClient {

    /**
     * Sends a conversation to the AI model with tool definitions, enabling the
     * model to request tool calls in its response.
     *
     * <p>The returned {@link ToolUseResponse} contains raw content blocks that may
     * include both {@code text} and {@code tool_use} entries. Callers should inspect
     * {@link ToolUseResponse#hasToolUse()} to determine if the model is requesting
     * tool execution, then feed results back via a subsequent call with
     * {@code tool_result} content blocks appended to the messages list.</p>
     *
     * <h4>Message Format</h4>
     * <p>Each message is a map with {@code "role"} ({@code "user"} or
     * {@code "assistant"}) and {@code "content"} (a {@code String} for simple text,
     * or a {@code List} of content blocks for multi-block messages including
     * {@code text}, {@code tool_use}, and {@code tool_result} types).</p>
     *
     * <h4>Tool Definition Format</h4>
     * <p>Each tool map contains {@code "name"}, {@code "description"}, and
     * {@code "input_schema"} (a JSON Schema object describing the tool's
     * parameters).</p>
     *
     * @param systemPrompt the system-level instruction that guides model behavior;
     *                     must not be {@code null}
     * @param messages     the conversation history as an ordered list of message maps
     *                     supporting text, tool_use, and tool_result content blocks
     * @param tools        tool definitions in the Claude Messages API format
     * @return a {@link ToolUseResponse} containing content blocks, stop reason, and
     *         token usage
     * @throws Exception if the API call fails (network errors, rate limits,
     *                   authentication failures, or model errors)
     */
    ToolUseResponse chatWithTools(String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) throws Exception;

    /**
     * Sends a simple single-turn chat message to the AI model without tool support.
     *
     * <p>This is a convenience method for scenarios that do not require tool-use,
     * such as generating summaries, formatting text, or answering direct questions.
     * For multi-turn conversations or tool-enabled interactions, use
     * {@link #chatWithTools} instead.</p>
     *
     * @param systemPrompt the system-level instruction that guides model behavior;
     *                     must not be {@code null}
     * @param userMessage  the user's message content; must not be {@code null}
     * @return the AI model's text response
     * @throws Exception if the API call fails (network errors, rate limits,
     *                   authentication failures, or model errors)
     */
    String chat(String systemPrompt, String userMessage) throws Exception;

    /**
     * Returns the model identifier currently configured for this client instance.
     *
     * @return the model identifier string (e.g., {@code "claude-sonnet-4-20250514"},
     *         {@code "claude-opus-4-6"}); never {@code null}
     */
    String getModel();

    /**
     * Returns a new client instance configured with the specified model, leaving
     * the current instance unchanged.
     *
     * <p>This is part of the immutable builder pattern. The returned instance
     * inherits all other configuration (max tokens, temperature) from the
     * current instance.</p>
     *
     * @param model the model identifier to use (e.g., {@code "claude-sonnet-4-20250514"});
     *              must not be {@code null}
     * @return a new {@code AiClient} instance configured with the specified model
     */
    AiClient withModel(String model);

    /**
     * Returns a new client instance configured with the specified maximum token
     * limit for model responses, leaving the current instance unchanged.
     *
     * <p>This controls the maximum length of the model's response. Higher values
     * allow longer responses but increase cost and latency.</p>
     *
     * @param maxTokens the maximum number of tokens the model may generate;
     *                  must be positive
     * @return a new {@code AiClient} instance configured with the specified max tokens
     */
    AiClient withMaxTokens(int maxTokens);

    /**
     * Returns a new client instance configured with the specified sampling
     * temperature, leaving the current instance unchanged.
     *
     * <p>Temperature controls the randomness of the model's output:
     * <ul>
     *   <li>{@code 0.0} -- deterministic, most likely tokens chosen</li>
     *   <li>{@code 1.0} -- maximum randomness</li>
     * </ul>
     * Lower values are recommended for code generation and structured output;
     * higher values for creative tasks.</p>
     *
     * @param temperature the sampling temperature, typically in the range
     *                    {@code [0.0, 1.0]}
     * @return a new {@code AiClient} instance configured with the specified temperature
     */
    AiClient withTemperature(double temperature);

    /**
     * Encapsulates the response from an AI model when tools are available.
     *
     * <p>The response contains an array of content blocks (in Claude API format)
     * that may include both {@code text} blocks and {@code tool_use} blocks. The
     * {@code stopReason} indicates why the model stopped generating:</p>
     * <ul>
     *   <li>{@code "end_turn"} -- the model finished its response naturally</li>
     *   <li>{@code "tool_use"} -- the model is requesting one or more tool calls</li>
     *   <li>{@code "max_tokens"} -- the response was truncated due to token limits</li>
     * </ul>
     *
     * @param contentBlocks the raw content blocks from the model response as a
     *                      Jackson {@link ArrayNode}; each element is a JSON object
     *                      with a {@code "type"} field ({@code "text"} or
     *                      {@code "tool_use"})
     * @param stopReason    the reason the model stopped generating
     *                      ({@code "end_turn"}, {@code "tool_use"}, or
     *                      {@code "max_tokens"})
     * @param inputTokens   the number of input tokens consumed (prompt + history)
     * @param outputTokens  the number of output tokens generated by the model
     * @since 1.0
     */
    record ToolUseResponse(
            ArrayNode contentBlocks,
            String stopReason,
            int inputTokens,
            int outputTokens) {

        /**
         * Returns {@code true} if the model stopped because it wants to invoke
         * one or more tools. When this returns {@code true}, callers should parse
         * {@code tool_use} blocks from {@link #contentBlocks()}, execute the
         * requested tools, and send the results back in a follow-up request.
         *
         * @return {@code true} if {@code stopReason} is {@code "tool_use"}
         */
        public boolean hasToolUse() {
            return "tool_use".equals(stopReason);
        }

        /**
         * Extracts and concatenates the text content from all {@code text}-type
         * content blocks in the response.
         *
         * <p>This is useful for retrieving the model's natural language output
         * while ignoring any tool_use blocks. If no text blocks are present,
         * returns an empty string.</p>
         *
         * @return the concatenated text from all text blocks; never {@code null}
         */
        public String getText() {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentBlocks) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            return sb.toString();
        }

        /**
         * Returns the total number of tokens consumed by this request-response
         * cycle, combining both input and output tokens.
         *
         * <p>Useful for cost tracking and monitoring token budgets.</p>
         *
         * @return the sum of {@link #inputTokens()} and {@link #outputTokens()}
         */
        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
