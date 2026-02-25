package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.AiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with Claude models via AWS Bedrock Runtime.
 *
 * <p>
 * Supports two invocation modes:
 * <ul>
 * <li><b>Blocking</b> ({@code invokeModel}): full response returned when ready.
 * Used for tool-use turns ({@link #chatWithTools}) where the whole response
 * is needed before deciding which tool to invoke.</li>
 * <li><b>Streaming</b> ({@code converseStream}): chunks received incrementally.
 * Used for simple {@link #chat} calls where only text accumulation is needed,
 * reducing peak memory usage and Lambda idle time.</li>
 * </ul>
 *
 * <p>
 * Shared JSON parsing logic lives in {@link AnthropicResponseParser} to avoid
 * duplication with {@link ClaudeClient}.
 *
 * <p>
 * BedRock model IDs:
 * <ul>
 * <li>global.anthropic.claude-sonnet-4-6</li>
 * <li>global.anthropic.claude-opus-4-6</li>
 * <li>anthropic.claude-3-5-sonnet-20240620-v1:0</li>
 * </ul>
 */
@Slf4j
public class BedrockClient implements AiClient, AiProvider {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int MAX_CONTINUATIONS = 5;
    private static final double DEFAULT_TEMPERATURE = 0.2;
    private static final Duration API_CALL_TIMEOUT = Duration.ofMinutes(10);

    // Maps Anthropic model names → Bedrock inference-profile IDs
    private static final Map<String, String> MODEL_MAPPING = new HashMap<>();
    static {
        // Claude 4.6 — use global inference profiles for on-demand throughput
        MODEL_MAPPING.put("claude-opus-4-6", "global.anthropic.claude-opus-4-6");
        MODEL_MAPPING.put("claude-sonnet-4-6", "global.anthropic.claude-sonnet-4-6");
        // Claude 4.5
        MODEL_MAPPING.put("claude-opus-4-5", "anthropic.claude-opus-4-5-20251101-v1:0");
        // Claude 3.5 Sonnet
        MODEL_MAPPING.put("claude-3-5-sonnet-20240620", "anthropic.claude-3-5-sonnet-20240620-v1:0");
        // Claude 3
        MODEL_MAPPING.put("claude-3-opus-20240229", "anthropic.claude-3-opus-20240229-v1:0");
        MODEL_MAPPING.put("claude-3-sonnet-20240229", "anthropic.claude-3-sonnet-20240229-v1:0");
        MODEL_MAPPING.put("claude-3-haiku-20240307", "anthropic.claude-3-haiku-20240307-v1:0");
        // Legacy
        MODEL_MAPPING.put("claude-sonnet-4-20250514", "anthropic.claude-sonnet-4-20250514-v1:0");
    }

    private final BedrockRuntimeClient bedrockClient;
    private final String model;
    private final String bedrockModelId;
    private final int maxTokens;
    private final double temperature;
    private final Region region;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicResponseParser responseParser = new AnthropicResponseParser(objectMapper);

    // Constructors ─────────────────────────────────────────────────────────────

    public BedrockClient(String model) {
        this(model, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE, Region.US_EAST_1, null, null);
    }

    public BedrockClient(String model, Region region) {
        this(model, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE, region, null, null);
    }

    public BedrockClient(String model, int maxTokens, double temperature, Region region) {
        this(model, maxTokens, temperature, region, null, null);
    }

    /**
     * Full-configuration constructor. Null accessKey/secretKey → default IAM
     * credential chain.
     */
    public BedrockClient(String model, int maxTokens, double temperature,
            Region region, String accessKey, String secretKey) {
        this.model = model;
        this.bedrockModelId = mapToBedrockModelId(model);
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.region = region;

        var clientBuilder = BedrockRuntimeClient.builder()
                .region(region)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .socketTimeout(API_CALL_TIMEOUT)
                        .connectionTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(API_CALL_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build());

        if (accessKey != null && secretKey != null) {
            clientBuilder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        }

        this.bedrockClient = clientBuilder.build();
    }

    // ─── Model mapping ────────────────────────────────────────────────────────

    private String mapToBedrockModelId(String anthropicModel) {
        if (anthropicModel.startsWith("anthropic.") || anthropicModel.startsWith("global.")) {
            return anthropicModel; // already a Bedrock/inference-profile ID
        }
        return MODEL_MAPPING.getOrDefault(anthropicModel, anthropicModel);
    }

    // ─── AiClient: with-methods ──────────────────────────────────────────────

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public BedrockClient withModel(String model) {
        return new BedrockClient(model, this.maxTokens, this.temperature, this.region, null, null);
    }

    @Override
    public BedrockClient withMaxTokens(int maxTokens) {
        return new BedrockClient(this.model, maxTokens, this.temperature, this.region, null, null);
    }

    @Override
    public BedrockClient withTemperature(double temperature) {
        return new BedrockClient(this.model, this.maxTokens, temperature, this.region, null, null);
    }

    // ─── AiProvider ──────────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "bedrock-claude";
    }

    @Override
    public AiProvider.ChatResponse chat(OperationContext context, String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        try {
            log.info("BedrockClient.chat SPI for tenant={}",
                    context != null ? context.getTenantId() : "none");
            AiClient.ToolUseResponse response = chatWithTools(systemPrompt, messages, tools);
            return responseParser.toChatResponse(response);
        } catch (Exception e) {
            log.error("Bedrock SPI chat failed: {}", e.getMessage());
            throw new RuntimeException("Bedrock chat failed", e);
        }
    }

    // ─── AiClient: tool-use (blocking invokeModel) ────────────────────────────

    /**
     * Sends messages + tool definitions via {@code invokeModel} (blocking).
     * The entire response is buffered in memory before returning, which is the
     * correct behaviour for tool-use turns — we need to know the full tool call
     * (including {@code tool_use} blocks) before executing tools.
     */
    @Override
    public AiClient.ToolUseResponse chatWithTools(String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) throws Exception {
        JsonNode responseJson = invokeModelRaw(systemPrompt, messages, tools, this.maxTokens);
        return responseParser.parseToolUseResponse(responseJson);
    }

    // ─── AiClient: simple chat (streaming) ───────────────────────────────────

    /**
     * Sends a simple user message and returns the accumulated text response.
     *
     * <p>
     * Uses the native {@code converse()} API for cleaner typed integration.
     * Falls back to {@code invokeModel} if converse fails.
     */
    @Override
    public String chat(String systemPrompt, String userMessage) throws Exception {
        try {
            return chatViaConverseApi(systemPrompt, userMessage);
        } catch (Exception e) {
            log.warn("Bedrock converse API failed, falling back to invokeModel: {}", e.getMessage());
            return chatBlocking(systemPrompt, userMessage);
        }
    }

    /**
     * Sends a single-turn message using the native typed {@code converse()}
     * Converse API.
     * This is the primary path for simple (non-tool-use) chat calls.
     */
    String chatViaConverseApi(String systemPrompt, String userMessage) throws Exception {
        ConverseRequest request = ConverseRequest.builder()
                .modelId(bedrockModelId)
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userMessage))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(this.maxTokens)
                        .temperature((float) this.temperature)
                        .build())
                .build();

        log.info("Bedrock converse API: model={}, maxTokens={}, msgLen={}",
                bedrockModelId, maxTokens, userMessage.length());

        try {
            ConverseResponse response = bedrockClient.converse(request);

            String stopReason = response.stopReason() != null ? response.stopReason().toString() : "unknown";
            int inputTokens = response.usage() != null ? response.usage().inputTokens() : 0;
            int outputTokens = response.usage() != null ? response.usage().outputTokens() : 0;

            log.info("Bedrock converse complete: stopReason={}, inputTokens={}, outputTokens={}",
                    stopReason, inputTokens, outputTokens);

            // Extract text from the typed Converse API response
            StringBuilder result = new StringBuilder();
            if (response.output() != null && response.output().message() != null) {
                for (ContentBlock block : response.output().message().content()) {
                    if (block.text() != null) {
                        result.append(block.text());
                    }
                }
            }
            return result.toString();
        } catch (BedrockRuntimeException e) {
            log.error("Bedrock converse API exception: {}", e.getMessage());
            throw new RuntimeException("Bedrock converse failed: " + e.getMessage(), e);
        }
    }

    /** Fallback: non-streaming single-turn chat via {@code invokeModel}. */
    private String chatBlocking(String systemPrompt, String userMessage) throws Exception {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", userMessage));
        JsonNode responseJson = invokeModelRaw(systemPrompt, messages, null, this.maxTokens);
        AnthropicResponseParser.ParsedChatResponse parsed = responseParser.parseChatResponse(responseJson);
        log.info("Bedrock blocking chat: stopReason={}", parsed.stopReason());
        return parsed.text();
    }

    // ─── Core HTTP layer (invokeModel) ────────────────────────────────────────

    /**
     * Sends a request to Bedrock using {@code invokeModel} and returns the raw
     * Anthropic JSON response. The Bedrock request body uses the same schema as
     * the Anthropic direct API, with {@code anthropic_version} added.
     */
    private JsonNode invokeModelRaw(String systemPrompt, List<Map<String, Object>> messages,
            List<Map<String, Object>> tools, int maxTokens) throws Exception {
        Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("anthropic_version", "bedrock-2023-05-31");
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        requestBody.put("system", systemPrompt);
        requestBody.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        String requestBodyJson = objectMapper.writeValueAsString(requestBody);
        log.info("Bedrock invokeModel: model={}, max_tokens={}, tools={}, bodyLen={}",
                bedrockModelId, maxTokens, tools != null ? tools.size() : 0, requestBodyJson.length());

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(bedrockModelId)
                .body(software.amazon.awssdk.core.SdkBytes.fromUtf8String(requestBodyJson))
                .build();

        try {
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            return objectMapper.readTree(response.body().asUtf8String());
        } catch (BedrockRuntimeException e) {
            log.error("Bedrock runtime exception: {}", e.getMessage());
            throw new RuntimeException("Bedrock invokeModel failed: " + e.getMessage(), e);
        }
    }

    // ─── Static factory ───────────────────────────────────────────────────────

    /**
     * Creates a BedrockClient from Secrets Manager.
     * Expects optional {@code awsAccessKey} and {@code awsSecretKey} in the
     * secret JSON. Falls back to the default IAM credential chain if absent.
     */
    public static BedrockClient fromSecrets(software.amazon.awssdk.services.secretsmanager.SecretsManagerClient sm,
            String secretArn, String model, String regionName) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            var req = software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.builder()
                    .secretId(secretArn).build();
            String secretJson = sm.getSecretValue(req).secretString();
            if (secretJson == null || secretJson.isBlank()) {
                throw new IllegalArgumentException("Bedrock credentials secret is empty");
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

    /** Closes the underlying Bedrock SDK client. */
    public void close() {
        if (bedrockClient != null) {
            bedrockClient.close();
        }
    }
}
