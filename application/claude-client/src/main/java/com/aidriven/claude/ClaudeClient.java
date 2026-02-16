package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with Claude API.
 * Supports auto-continuation: if Claude's response is truncated
 * (stop_reason=max_tokens),
 * it automatically sends follow-up requests to get the complete response.
 */
@Slf4j
public class ClaudeClient implements AiClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-opus-4-6";
    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 32768;
    private static final int MAX_CONTINUATIONS = 5;
    private static final double DEFAULT_TEMPERATURE = 0.2;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public ClaudeClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public ClaudeClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }

    public ClaudeClient(String apiKey, String model, int maxTokens, double temperature) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    public String getModel() {
        return model;
    }

    public ClaudeClient withModel(String model) {
        return new ClaudeClient(this.apiKey, model, this.maxTokens, this.temperature);
    }

    public ClaudeClient withMaxTokens(int maxTokens) {
        return new ClaudeClient(this.apiKey, this.model, maxTokens, this.temperature);
    }

    public ClaudeClient withTemperature(double temperature) {
        return new ClaudeClient(this.apiKey, this.model, this.maxTokens, temperature);
    }

    /**
     * Creates a ClaudeClient from secrets.
     */
    public static ClaudeClient fromSecrets(SecretsService secretsService, String secretArn) {
        try {
            String apiKey = secretsService.getSecret(secretArn);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("Claude API key secret is empty or missing");
            }
            return new ClaudeClient(apiKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ClaudeClient from secrets", e);
        }
    }

    /**
     * Sends a message to Claude and returns the response.
     * Automatically continues if the response is truncated.
     */
    public String chat(String systemPrompt, String userMessage) throws Exception {
        return chat(systemPrompt, userMessage, this.maxTokens);
    }

    /**
     * Sends a message to Claude with specified max tokens.
     * Automatically continues if the response is truncated.
     */
    public String chat(String systemPrompt, String userMessage, int maxTokens) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userMessage));

        StringBuilder fullResponse = new StringBuilder();
        int continuation = 0;

        while (continuation <= MAX_CONTINUATIONS) {
            ApiResponse apiResponse = sendRequest(systemPrompt, messages, maxTokens);
            fullResponse.append(apiResponse.text);

            if (!"max_tokens".equals(apiResponse.stopReason)) {
                // Response complete
                break;
            }

            // Response was truncated — continue the conversation
            continuation++;
            log.info("Response truncated (stop_reason=max_tokens), auto-continuing ({}/{})",
                    continuation, MAX_CONTINUATIONS);

            if (continuation > MAX_CONTINUATIONS) {
                log.warn("Reached max continuations ({}), returning partial response", MAX_CONTINUATIONS);
                break;
            }

            // Add assistant's partial response and a user message asking to continue
            messages.add(Map.of("role", "assistant", "content", apiResponse.text));

            boolean isJsonResponse = fullResponse.toString().trim().startsWith("{");
            String continuationPrompt = isJsonResponse
                    ? "Your JSON response was truncated. Continue the JSON output from EXACTLY "
                            + "where it stopped. Output ONLY the remaining JSON characters. "
                            + "Do NOT add any text, explanation, or markdown before or after the JSON continuation. "
                            + "The output will be concatenated directly to your previous response to form valid JSON."
                    : "Your response was truncated. Continue EXACTLY from where you left off. "
                            + "Do not repeat any content. Do not add any preamble.";
            messages.add(Map.of("role", "user", "content", continuationPrompt));
        }

        log.info("Claude response complete: {} chars, {} continuations", fullResponse.length(), continuation);
        return fullResponse.toString();
    }

    /**
     * Sends a multi-turn conversation to Claude.
     */
    public String chatWithHistory(String systemPrompt, List<Message> messageHistory) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message msg : messageHistory) {
            messages.add(Map.of("role", msg.role(), "content", msg.content()));
        }

        StringBuilder fullResponse = new StringBuilder();
        int continuation = 0;

        while (continuation <= MAX_CONTINUATIONS) {
            ApiResponse apiResponse = sendRequest(systemPrompt, messages, this.maxTokens);
            fullResponse.append(apiResponse.text);

            if (!"max_tokens".equals(apiResponse.stopReason)) {
                break;
            }

            continuation++;
            if (continuation > MAX_CONTINUATIONS) {
                log.warn("Reached max continuations ({}), returning partial response", MAX_CONTINUATIONS);
                break;
            }

            log.info("Response truncated, auto-continuing ({}/{})", continuation, MAX_CONTINUATIONS);
            messages.add(Map.of("role", "assistant", "content", apiResponse.text));

            boolean isJsonResponse = fullResponse.toString().trim().startsWith("{");
            String continuationPrompt = isJsonResponse
                    ? "Your JSON response was truncated. Continue the JSON output from EXACTLY "
                            + "where it stopped. Output ONLY the remaining JSON characters. "
                            + "Do NOT add any text, explanation, or markdown before or after the JSON continuation. "
                            + "The output will be concatenated directly to your previous response to form valid JSON."
                    : "Your response was truncated. Continue EXACTLY from where you left off. "
                            + "Do not repeat any content. Do not add any preamble.";
            messages.add(Map.of("role", "user", "content", continuationPrompt));
        }

        return fullResponse.toString();
    }

    /**
     * Sends a message to Claude with tool definitions.
     * Returns the raw response including tool_use content blocks.
     * This is the primary entry point for the agent orchestrator.
     *
     * @param systemPrompt System prompt
     * @param messages     Conversation messages (supports text, tool_use,
     *                     tool_result blocks)
     * @param tools        Tool definitions in Claude API format
     * @return Raw response with content blocks and stop reason
     */
    @Override
    public AiClient.ToolUseResponse chatWithTools(String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) throws Exception {
        JsonNode responseJson = sendRequestRaw(systemPrompt, messages, tools, this.maxTokens);

        String stopReason = responseJson.has("stop_reason")
                ? responseJson.get("stop_reason").asText()
                : "unknown";

        ArrayNode contentBlocks = (ArrayNode) responseJson.get("content");
        if (contentBlocks == null) {
            contentBlocks = objectMapper.createArrayNode();
        }

        int inputTokens = 0, outputTokens = 0;
        JsonNode usage = responseJson.get("usage");
        if (usage != null) {
            inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
            outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
        }

        return new AiClient.ToolUseResponse(contentBlocks, stopReason, inputTokens, outputTokens);
    }

    private ApiResponse sendRequest(String systemPrompt, List<Map<String, Object>> messages, int maxTokens)
            throws Exception {
        JsonNode responseJson = sendRequestRaw(systemPrompt, messages, null, maxTokens);

        String stopReason = responseJson.has("stop_reason")
                ? responseJson.get("stop_reason").asText()
                : "unknown";
        log.info("Claude stop_reason: {}", stopReason);

        // Log token usage if available
        JsonNode usage = responseJson.get("usage");
        if (usage != null) {
            log.info("Claude token usage: input={}, output={}",
                    usage.has("input_tokens") ? usage.get("input_tokens").asInt() : "?",
                    usage.has("output_tokens") ? usage.get("output_tokens").asInt() : "?");
        }

        String text = extractTextContent(responseJson);
        return new ApiResponse(text, stopReason);
    }

    /**
     * Core API call method. Sends a request to Claude and returns the raw JSON
     * response.
     * Supports optional tools parameter for tool-use mode.
     */
    private JsonNode sendRequestRaw(String systemPrompt, List<Map<String, Object>> messages,
            List<Map<String, Object>> tools, int maxTokens) throws Exception {
        Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        requestBody.put("system", systemPrompt);
        requestBody.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        String requestBodyJson = objectMapper.writeValueAsString(requestBody);

        // Log the full messages array for debugging tool_result structure
        log.info("Claude request messages count={}", messages != null ? messages.size() : 0);
        for (int i = 0; i < (messages != null ? messages.size() : 0); i++) {
            log.info("Message[{}]: {}", i, objectMapper.writeValueAsString(messages.get(i)));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .timeout(Duration.ofMinutes(10))
                .build();

        log.info("Sending request to Claude API (model={}, max_tokens={}, tools={}, bodyLen={})",
                model, maxTokens, tools != null ? tools.size() : 0, requestBodyJson.length());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Claude API error (HTTP {}): {}", response.statusCode(), response.body());
            log.error("Failed request body: {}", requestBodyJson);
            throw new RuntimeException("Claude API error: " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    private String extractTextContent(JsonNode json) {
        JsonNode content = json.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new RuntimeException("Unexpected response format from Claude API");
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode block : content) {
            JsonNode typeNode = block.get("type");
            if (typeNode != null && "text".equals(typeNode.asText())) {
                result.append(block.get("text").asText());
            }
        }
        return result.toString();
    }

    private record ApiResponse(String text, String stopReason) {
    }

    // ToolUseResponse is defined in AiClient (the interface this class implements).
    // Use AiClient.ToolUseResponse throughout.

    public record Message(String role, String content) {
        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
