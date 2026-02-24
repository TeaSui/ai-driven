package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.service.SecretsService;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.AiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with Claude via AWS BedRock Runtime.
 * Implements the same interface as ClaudeClient but uses BedRock instead of
 * Anthropic's direct API.
 *
 * <p>BedRock model IDs:
 * <ul>
 *   <li>anthropic.claude-sonnet-4-6 (Claude Opus 4.6)</li>
 *   <li>anthropic.claude-3-5-sonnet-20240620-v1:0</li>
 *   <li>anthropic.claude-3-opus-20240229-v1:0</li>
 *   <li>anthropic.claude-3-sonnet-20240229-v1:0</li>
 *   <li>anthropic.claude-3-haiku-20240307-v1:0</li>
 * </ul>
 *
 * <p>Supports auto-continuation for responses truncated by max_tokens.
 */
@Slf4j
public class BedrockClient implements AiClient, AiProvider {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int MAX_CONTINUATIONS = 5;
    private static final double DEFAULT_TEMPERATURE = 0.2;

    // BedRock model ID mappings from Anthropic model names
    // Maps the Anthropic API model names to BedRock model IDs
    private static final Map<String, String> MODEL_MAPPING = new HashMap<>();
    static {
        // Claude 4.6 models (latest) - use exact BedRock model IDs
        MODEL_MAPPING.put("claude-opus-4-6", "anthropic.claude-opus-4-6-v1:0");
        MODEL_MAPPING.put("claude-sonnet-4-6", "anthropic.claude-sonnet-4-6");
        // Claude 4.5 models
        MODEL_MAPPING.put("claude-opus-4-5", "anthropic.claude-opus-4-5-20251101-v1:0");
        // Claude 3.5 Sonnet
        MODEL_MAPPING.put("claude-3-5-sonnet-20240620", "anthropic.claude-3-5-sonnet-20240620-v1:0");
        // Claude 3 models
        MODEL_MAPPING.put("claude-3-opus-20240229", "anthropic.claude-3-opus-20240229-v1:0");
        MODEL_MAPPING.put("claude-3-sonnet-20240229", "anthropic.claude-3-sonnet-20240229-v1:0");
        MODEL_MAPPING.put("claude-3-haiku-20240307", "anthropic.claude-3-haiku-20240307-v1:0");
        // Legacy aliases
        MODEL_MAPPING.put("claude-sonnet-4-20250514", "anthropic.claude-sonnet-4-20250514-v1:0");
    }

    private final BedrockRuntimeClient bedrockClient;
    private final String model;
    private final String bedrockModelId;
    private final int maxTokens;
    private final double temperature;
    private final Region region;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a BedrockClient with the specified model.
     *
     * @param model Anthropic model name (will be mapped to BedRock model ID)
     */
    public BedrockClient(String model) {
        this(model, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE, Region.US_EAST_1, null, null);
    }

    /**
     * Creates a BedrockClient with the specified model and region.
     *
     * @param model Anthropic model name
     * @param region AWS region
     */
    public BedrockClient(String model, Region region) {
        this(model, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE, region, null, null);
    }

    /**
     * Creates a BedrockClient with model, tokens, temperature, and region.
     * Uses default AWS credential provider chain (no explicit credentials).
     *
     * @param model Anthropic model name
     * @param maxTokens Maximum tokens per response
     * @param temperature Temperature for sampling
     * @param region AWS region
     */
    public BedrockClient(String model, int maxTokens, double temperature, Region region) {
        this(model, maxTokens, temperature, region, null, null);
    }

    /**
     * Creates a BedrockClient with full configuration.
     *
     * @param model Anthropic model name
     * @param maxTokens Maximum tokens per response
     * @param temperature Temperature for sampling
     * @param region AWS region
     * @param accessKey AWS access key (optional, uses default chain if null)
     * @param secretKey AWS secret key (optional, uses default chain if null)
     */
    public BedrockClient(String model, int maxTokens, double temperature,
            Region region, String accessKey, String secretKey) {
        this.model = model;
        this.bedrockModelId = mapToBedrockModelId(model);
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.region = region;

        var clientBuilder = BedrockRuntimeClient.builder()
                .region(region);

        if (accessKey != null && secretKey != null) {
            clientBuilder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        }

        this.bedrockClient = clientBuilder.build();
    }

    /**
     * Creates a BedrockClient from secrets.
     * Expects secret JSON to contain optional awsAccessKey and awsSecretKey.
     */
    public static BedrockClient fromSecrets(SecretsService secretsService, String secretArn,
            String model, String regionName) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            String secretJson = secretsService.getSecret(secretArn);
            if (secretJson == null || secretJson.trim().isEmpty()) {
                throw new IllegalArgumentException("BedRock credentials secret is empty or missing");
            }

            JsonNode secret = mapper.readTree(secretJson);
            String accessKey = secret.has("awsAccessKey") ? secret.get("awsAccessKey").asText() : null;
            String secretKey = secret.has("awsSecretKey") ? secret.get("awsSecretKey").asText() : null;

            Region region = regionName != null ? Region.of(regionName) : Region.US_EAST_1;

            return new BedrockClient(model, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE,
                    region, accessKey, secretKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create BedrockClient from secrets", e);
        }
    }

    /**
     * Maps Anthropic model name to BedRock model ID.
     * If the input starts with "anthropic.", returns it as-is (already a BedRock ID).
     */
    private String mapToBedrockModelId(String anthropicModel) {
        // If it's already a BedRock model ID, use it directly
        if (anthropicModel.startsWith("anthropic.")) {
            return anthropicModel;
        }
        return MODEL_MAPPING.getOrDefault(anthropicModel, anthropicModel);
    }

    public String getModel() {
        return model;
    }

    @Override
    public BedrockClient withModel(String model) {
        return new BedrockClient(model, this.maxTokens, this.temperature,
                this.region, null, null);
    }

    @Override
    public BedrockClient withMaxTokens(int maxTokens) {
        return new BedrockClient(this.model, maxTokens, this.temperature,
                this.region, null, null);
    }

    @Override
    public BedrockClient withTemperature(double temperature) {
        return new BedrockClient(this.model, this.maxTokens, temperature,
                this.region, null, null);
    }

    @Override
    public String getName() {
        return "bedrock-claude";
    }

    @Override
    public String chat(String systemPrompt, String userMessage) throws Exception {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", userMessage)
        );

        JsonNode responseJson = sendRequestRaw(systemPrompt, messages, null, this.maxTokens);

        String stopReason = responseJson.has("stop_reason")
                ? responseJson.get("stop_reason").asText()
                : "unknown";

        log.info("Bedrock stop_reason: {}", stopReason);

        // Log token usage if available
        JsonNode usage = responseJson.get("usage");
        if (usage != null) {
            log.info("Bedrock token usage: input={}, output={}",
                    usage.has("input_tokens") ? usage.get("input_tokens").asInt() : "?",
                    usage.has("output_tokens") ? usage.get("output_tokens").asInt() : "?");
        }

        return extractTextContent(responseJson);
    }

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

    private String extractTextContent(JsonNode json) {
        JsonNode content = json.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new RuntimeException("Unexpected response format from Bedrock API");
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

    @Override
    public ChatResponse chat(OperationContext context, String systemPrompt,
            List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        try {
            log.info("BedrockClient.chat called via SPI for tenant={}",
                    context != null ? context.getTenantId() : "none");
            AiClient.ToolUseResponse response = chatWithTools(systemPrompt, messages, tools);
            return new BedrockChatResponse(response, objectMapper);
        } catch (Exception e) {
            log.error("Bedrock SPI chat failed: {}", e.getMessage());
            throw new RuntimeException("Bedrock chat failed", e);
        }
    }

    @RequiredArgsConstructor
    private static class BedrockChatResponse implements AiProvider.ChatResponse {
        private final AiClient.ToolUseResponse response;
        private final ObjectMapper objectMapper;

        @Override
        public String getText() {
            return response.getText();
        }

        @Override
        public List<Map<String, Object>> getToolCalls() {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (JsonNode block : response.contentBlocks()) {
                if ("tool_use".equals(block.path("type").asText())) {
                    calls.add(objectMapper.convertValue(block, Map.class));
                }
            }
            return calls;
        }

        @Override
        public int getInputTokens() {
            return response.inputTokens();
        }

        @Override
        public int getOutputTokens() {
            return response.outputTokens();
        }
    }

    /**
     * Core API call method. Sends a request to BedRock and returns the raw JSON
     * response.
     * Supports optional tools parameter for tool-use mode.
     */
    private JsonNode sendRequestRaw(String systemPrompt, List<Map<String, Object>> messages,
            List<Map<String, Object>> tools, int maxTokens) throws Exception {

        // Build request body in Anthropic format (BedRock uses the same format)
        Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("anthropic_version", "anthropic-version-2023-06-01");
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        requestBody.put("system", systemPrompt);
        requestBody.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        String requestBodyJson = objectMapper.writeValueAsString(requestBody);

        log.info("Bedrock request messages count={}", messages != null ? messages.size() : 0);
        for (int i = 0; i < (messages != null ? messages.size() : 0); i++) {
            log.debug("Message[{}]: {}", i, objectMapper.writeValueAsString(messages.get(i)));
        }

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(bedrockModelId)
                .body(software.amazon.awssdk.core.SdkBytes.fromUtf8String(requestBodyJson))
                .build();

        log.info("Sending request to BedRock (model={}, max_tokens={}, tools={}, bodyLen={})",
                bedrockModelId, maxTokens, tools != null ? tools.size() : 0, requestBodyJson.length());

        try {
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            String responseBody = response.body().asUtf8String();

            // InvokeModelResponse doesn't have a statusCode() method
            // BedrockRuntimeException is thrown for errors
            return objectMapper.readTree(responseBody);
        } catch (BedrockRuntimeException e) {
            log.error("Bedrock runtime exception: {}", e.getMessage());
            throw new RuntimeException("Bedrock API error: " + e.getMessage(), e);
        }
    }

    /**
     * Closes the BedRock client.
     */
    public void close() {
        if (bedrockClient != null) {
            bedrockClient.close();
        }
    }
}
