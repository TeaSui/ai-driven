package com.aidriven.claude;

import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Supports auto-continuation: if Claude's response is truncated (stop_reason=max_tokens),
 * it automatically sends follow-up requests to get the complete response.
 */
@Slf4j
public class ClaudeClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-opus-4-6";
    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 32768;
    private static final int MAX_CONTINUATIONS = 5;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public ClaudeClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public ClaudeClient(String apiKey, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * Creates a ClaudeClient from secrets.
     */
    public static ClaudeClient fromSecrets(SecretsService secretsService, String secretArn) {
        try {
            String apiKey = secretsService.getSecretString(secretArn);
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
        return chat(systemPrompt, userMessage, DEFAULT_MAX_TOKENS);
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
            ApiResponse apiResponse = sendRequest(systemPrompt, messages, DEFAULT_MAX_TOKENS);
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

    private ApiResponse sendRequest(String systemPrompt, List<Map<String, Object>> messages, int maxTokens)
            throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofMinutes(10))
                .build();

        log.info("Sending request to Claude API (model={}, max_tokens={})", model, maxTokens);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Claude API error (HTTP {}): {}", response.statusCode(), response.body());
            throw new RuntimeException("Claude API error: " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());

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

    private String extractTextContent(JsonNode json) {
        JsonNode content = json.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new RuntimeException("Unexpected response format from Claude API");
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.get("type").asText())) {
                result.append(block.get("text").asText());
            }
        }
        return result.toString();
    }

    private record ApiResponse(String text, String stopReason) {}

    public record Message(String role, String content) {
        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
