package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.service.SecretsService;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.AnthropicMessage;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest.CacheControl;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionResponse;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock.Type;
import org.springframework.ai.anthropic.api.AnthropicApi.Role;
import org.springframework.ai.anthropic.api.AnthropicApi.Tool;
import org.springframework.ai.anthropic.api.AnthropicApi.Usage;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring AI adapter implementing {@link AiClient} and {@link AiProvider}.
 *
 * <p>Uses Spring AI's {@link AnthropicChatModel} for simple chat (with retry + prompt caching),
 * and the low-level {@link AnthropicApi} for tool-use calls (maintaining full control over
 * raw content blocks required by {@code AgentOrchestrator}).
 *
 * <p>Prompt caching is enabled natively via Spring AI 1.1.2's {@link AnthropicCacheOptions}
 * for {@code chat()}, and via block-style system prompts with {@link CacheControl} markers
 * for {@code chatWithTools()}.
 *
 * <p>This is a library-only integration: no Spring Boot, no auto-configuration.
 * All instances are created manually via constructors or {@link #fromSecrets}.
 */
@Slf4j
public class SpringAiClientAdapter implements AiClient, AiProvider {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    private static final int DEFAULT_MAX_TOKENS = 32768;
    private static final double DEFAULT_TEMPERATURE = 0.2;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MS = 30000;

    private final AnthropicApi anthropicApi;
    private final AnthropicChatModel chatModel;
    private final RetryTemplate retryTemplate;
    private final ObjectMapper objectMapper;
    private final AnthropicResponseParser responseParser;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public SpringAiClientAdapter(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }

    public SpringAiClientAdapter(String apiKey, String model) {
        this(apiKey, model, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }

    public SpringAiClientAdapter(String apiKey, String model, int maxTokens, double temperature) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.objectMapper = new ObjectMapper();
        this.responseParser = new AnthropicResponseParser(objectMapper);
        this.retryTemplate = buildRetryTemplate();
        this.anthropicApi = AnthropicApi.builder().apiKey(apiKey).build();
        this.chatModel = buildChatModel();
    }

    /**
     * Creates a SpringAiClientAdapter from secrets.
     */
    public static SpringAiClientAdapter fromSecrets(SecretsService secretsService, String secretArn) {
        try {
            String apiKey = secretsService.getSecret(secretArn);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("Claude API key secret is empty or missing");
            }
            return new SpringAiClientAdapter(apiKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SpringAiClientAdapter from secrets", e);
        }
    }

    // --- AiClient implementation ---

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public AiClient withModel(String model) {
        return new SpringAiClientAdapter(this.apiKey, model, this.maxTokens, this.temperature);
    }

    @Override
    public AiClient withMaxTokens(int maxTokens) {
        return new SpringAiClientAdapter(this.apiKey, this.model, maxTokens, this.temperature);
    }

    @Override
    public AiClient withTemperature(double temperature) {
        return new SpringAiClientAdapter(this.apiKey, this.model, this.maxTokens, temperature);
    }

    /**
     * Simple chat using Spring AI's {@link AnthropicChatModel} with built-in retry
     * and prompt caching enabled via {@link AnthropicCacheOptions}.
     *
     * <p>Cache strategy {@link AnthropicCacheStrategy#SYSTEM_AND_TOOLS} caches the system
     * prompt, reducing latency and cost for repeated calls with the same system prompt.
     */
    @Override
    public String chat(String systemPrompt, String userMessage) throws Exception {
        AnthropicCacheOptions cacheOptions = AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                .messageTypeMinContentLength(MessageType.SYSTEM, 0)
                .build();

        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)),
                AnthropicChatOptions.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .cacheOptions(cacheOptions)
                        .build()
        );

        log.info("Spring AI chat: model={}, maxTokens={}, msgLen={}, cacheStrategy=SYSTEM_AND_TOOLS",
                model, maxTokens, userMessage.length());
        org.springframework.ai.chat.model.ChatResponse response = chatModel.call(prompt);
        String text = response.getResult().getOutput().getText();
        log.info("Spring AI chat complete: {} chars", text != null ? text.length() : 0);
        return text;
    }

    /**
     * Tool-use chat using low-level {@link AnthropicApi} with retry.
     * Maintains raw content block format required by {@code AgentOrchestrator}.
     */
    @Override
    public ToolUseResponse chatWithTools(String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) throws Exception {
        return retryTemplate.execute(ctx -> {
            if (ctx.getRetryCount() > 0) {
                log.info("Retry attempt {} for chatWithTools", ctx.getRetryCount());
            }
            return executeChatWithTools(systemPrompt, messages, tools);
        });
    }

    // --- AiProvider SPI implementation ---

    @Override
    public String getName() {
        return "spring-ai-claude";
    }

    @Override
    public AiProvider.ChatResponse chat(OperationContext context, String systemPrompt,
            List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        try {
            log.info("SpringAiClientAdapter.chat via SPI for tenant={}",
                    context != null ? context.getTenantId() : "none");
            ToolUseResponse response = chatWithTools(systemPrompt, messages, tools);
            return responseParser.toChatResponse(response);
        } catch (Exception e) {
            log.error("Spring AI SPI chat failed: {}", e.getMessage());
            throw new RuntimeException("Spring AI chat failed", e);
        }
    }

    // --- Internal methods ---

    /**
     * Executes a tool-use chat with Anthropic prompt caching enabled.
     *
     * <p>Uses Spring AI 1.1.2's typed {@link ChatCompletionRequest} builder with block-style
     * system prompt and {@link CacheControl} markers. The system prompt is sent as a list of
     * content blocks with {@code cache_control} for prompt caching:
     * <pre>
     * "system": [{"type": "text", "text": "...", "cache_control": {"type": "ephemeral"}}]
     * </pre>
     */
    private ToolUseResponse executeChatWithTools(String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        List<AnthropicMessage> apiMessages = convertMessages(messages);
        List<Tool> apiTools = convertTools(tools);

        // Build block-style system prompt with cache control for prompt caching
        List<ContentBlock> systemBlocks = List.of(
                new ContentBlock(systemPrompt, new CacheControl("ephemeral")));

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(apiMessages)
                .system(systemBlocks)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .stream(false)
                .tools(apiTools.isEmpty() ? null : apiTools)
                .build();

        log.info("Spring AI chatWithTools (cached): model={}, maxTokens={}, tools={}, messages={}",
                model, maxTokens, apiTools.size(), apiMessages.size());

        ResponseEntity<ChatCompletionResponse> responseEntity =
                anthropicApi.chatCompletionEntity(request);
        ChatCompletionResponse response = responseEntity.getBody();

        if (response == null) {
            throw new RuntimeException("Null response from Anthropic API");
        }

        logCacheUsage(response);
        return convertToToolUseResponse(response);
    }

    // --- Message conversion: our Map format -> Spring AI types ---

    @SuppressWarnings("unchecked")
    List<AnthropicMessage> convertMessages(List<Map<String, Object>> messages) {
        List<AnthropicMessage> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            Role apiRole = "assistant".equals(role) ? Role.ASSISTANT : Role.USER;

            if (content instanceof String text) {
                result.add(new AnthropicMessage(List.of(new ContentBlock(text)), apiRole));
            } else if (content instanceof List<?> blocks) {
                List<ContentBlock> contentBlocks = convertContentBlocks(
                        (List<Map<String, Object>>) blocks);
                result.add(new AnthropicMessage(contentBlocks, apiRole));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ContentBlock> convertContentBlocks(List<Map<String, Object>> blocks) {
        List<ContentBlock> result = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            String type = (String) block.get("type");
            if (type == null) {
                continue;
            }
            switch (type) {
                case "text" -> result.add(new ContentBlock((String) block.get("text")));
                case "tool_use" -> result.add(new ContentBlock(
                        Type.TOOL_USE, null, null, null,
                        (String) block.get("id"),
                        (String) block.get("name"),
                        objectMapper.convertValue(block.get("input"), Map.class),
                        null, null, null, null, null,
                        null, null, null, null, null, null));
                case "tool_result" -> result.add(new ContentBlock(
                        Type.TOOL_RESULT, null, null, null,
                        null, null, null,
                        (String) block.get("tool_use_id"),
                        block.get("content") instanceof String s ? s : objectMapper.convertValue(block.get("content"), String.class),
                        null, null, null,
                        null, null, null, null, null, null));
                default -> log.warn("Skipping unknown content block type: {}", type);
            }
        }
        return result;
    }

    // --- Tool conversion: our Map format -> Spring AI Tool records ---

    @SuppressWarnings("unchecked")
    List<Tool> convertTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .map(t -> new Tool(
                        (String) t.get("name"),
                        (String) t.get("description"),
                        (Map<String, Object>) t.get("input_schema")))
                .toList();
    }

    // --- Response conversion: Spring AI types -> our ToolUseResponse ---

    ToolUseResponse convertToToolUseResponse(ChatCompletionResponse response) {
        ArrayNode contentBlocks = objectMapper.createArrayNode();

        if (response.content() != null) {
            for (ContentBlock block : response.content()) {
                Type blockType = block.type();
                if (blockType == null) {
                    continue;
                }
                switch (blockType) {
                    case TEXT -> {
                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("type", "text");
                        node.put("text", block.text());
                        contentBlocks.add(node);
                    }
                    case TOOL_USE -> {
                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("type", "tool_use");
                        node.put("id", block.id());
                        node.put("name", block.name());
                        node.set("input", objectMapper.valueToTree(block.input()));
                        contentBlocks.add(node);
                    }
                    default -> log.debug("Skipping content block type in response: {}", blockType);
                }
            }
        }

        String stopReason = response.stopReason() != null ? response.stopReason() : "unknown";

        int inputTokens = 0;
        int outputTokens = 0;
        int cacheCreationTokens = 0;
        int cacheReadTokens = 0;
        Usage usage = response.usage();
        if (usage != null) {
            inputTokens = usage.inputTokens() != null ? usage.inputTokens() : 0;
            outputTokens = usage.outputTokens() != null ? usage.outputTokens() : 0;
            cacheCreationTokens = usage.cacheCreationInputTokens() != null
                    ? usage.cacheCreationInputTokens() : 0;
            cacheReadTokens = usage.cacheReadInputTokens() != null
                    ? usage.cacheReadInputTokens() : 0;
        }

        log.info("Spring AI response: stopReason={}, inputTokens={}, outputTokens={}, "
                        + "cacheCreationTokens={}, cacheReadTokens={}",
                stopReason, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens);

        return new ToolUseResponse(contentBlocks, stopReason, inputTokens, outputTokens);
    }

    // --- Prompt caching usage logging ---

    /**
     * Logs cache usage metrics from the API response.
     * Spring AI 1.1.2's Usage record includes cacheCreationInputTokens and
     * cacheReadInputTokens, enabling visibility into prompt caching effectiveness.
     */
    private void logCacheUsage(ChatCompletionResponse response) {
        Usage usage = response.usage();
        if (usage == null) {
            return;
        }
        int cacheCreation = usage.cacheCreationInputTokens() != null
                ? usage.cacheCreationInputTokens() : 0;
        int cacheRead = usage.cacheReadInputTokens() != null
                ? usage.cacheReadInputTokens() : 0;
        log.info("chatWithTools cache usage: inputTokens={}, outputTokens={}, "
                        + "cacheCreationInputTokens={}, cacheReadInputTokens={}",
                usage.inputTokens(), usage.outputTokens(), cacheCreation, cacheRead);
    }

    // --- Retry configuration ---

    private AnthropicChatModel buildChatModel() {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .retryTemplate(retryTemplate)
                .build();
    }

    private static RetryTemplate buildRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(MAX_RETRY_ATTEMPTS)
                .exponentialBackoff(INITIAL_BACKOFF_MS, BACKOFF_MULTIPLIER, MAX_BACKOFF_MS)
                .build();
    }
}
