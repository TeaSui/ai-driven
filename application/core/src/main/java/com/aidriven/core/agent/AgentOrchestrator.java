package com.aidriven.core.agent;

import com.aidriven.core.exception.AgentExecutionException;

import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.agent.model.ConversationMessage;
import com.aidriven.core.agent.model.TokenUsage;
import com.aidriven.core.observability.AgentMetrics;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.config.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The core ReAct (Reason + Act) loop for the AI agent.
 */
@Slf4j
public class AgentOrchestrator {

    private static final int DEFAULT_MAX_TURNS = 10;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiClient aiClient;
    private final ConversationWindowManager windowManager;
    private final CostTracker costTracker;
    private final GuardedToolRegistry guardedToolRegistry;
    private final ProgressTracker progressTracker;
    private final WorkflowContextProvider workflowContextProvider;
    private final int maxTurns;

    private AgentOrchestrator(Builder builder) {
        this.aiClient = Objects.requireNonNull(builder.aiClient, "aiClient");
        this.windowManager = builder.windowManager;
        this.costTracker = builder.costTracker;
        this.guardedToolRegistry = builder.guardedToolRegistry;
        this.progressTracker = builder.progressTracker;
        this.workflowContextProvider = builder.workflowContextProvider;
        this.maxTurns = builder.maxTurns;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private AiClient aiClient;
        private ConversationWindowManager windowManager;
        private CostTracker costTracker;
        private GuardedToolRegistry guardedToolRegistry;
        private ProgressTracker progressTracker;
        private WorkflowContextProvider workflowContextProvider;
        private int maxTurns = DEFAULT_MAX_TURNS;

        private Builder() {
        }

        public Builder aiClient(AiClient aiClient) {
            this.aiClient = aiClient;
            return this;
        }

        public Builder windowManager(ConversationWindowManager windowManager) {
            this.windowManager = windowManager;
            return this;
        }

        public Builder costTracker(CostTracker costTracker) {
            this.costTracker = costTracker;
            return this;
        }

        public Builder agentConfig(AgentConfig config) {
            this.maxTurns = config.maxTurns();
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder guardedToolRegistry(GuardedToolRegistry guardedToolRegistry) {
            this.guardedToolRegistry = guardedToolRegistry;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            if (toolRegistry != null) {
                this.guardedToolRegistry = new GuardedToolRegistry(toolRegistry, null, null, null, false, false);
            }
            return this;
        }

        public Builder progressTracker(ProgressTracker progressTracker) {
            this.progressTracker = progressTracker;
            return this;
        }

        public Builder workflowContextProvider(WorkflowContextProvider workflowContextProvider) {
            this.workflowContextProvider = workflowContextProvider;
            return this;
        }

        public AgentOrchestrator build() {
            return new AgentOrchestrator(this);
        }
    }

    public AgentResponse process(AgentRequest request) throws AgentExecutionException {
        return process(request, CommentIntent.AI_COMMAND);
    }

    public AgentResponse process(AgentRequest request, CommentIntent intent) throws AgentExecutionException {
        if (intent == null) {
            intent = CommentIntent.AI_COMMAND;
        }

        // Check budget constraint
        if (costTracker != null && !costTracker.hasRemainingBudget(request.ticketKey())) {
            log.warn("Cost budget exhausted for ticket={}", request.ticketKey());
            return new AgentResponse(
                    "This ticket has exceeded its token budget. Please contact an admin to increase the limit.",
                    List.of(), 0, 0);
        }

        // Initialize execution context
        String systemPrompt = buildSystemPrompt(request, intent);
        List<Map<String, Object>> toolSchemas = getToolSchemas(request);
        AgentMetrics metrics = initializeMetrics(request);
        long startTimeMs = System.currentTimeMillis();

        log.info("Agent processing ticket={} intent={} with {} tools",
                request.ticketKey(), intent, toolSchemas.size());

        // Initialize conversation
        String tenantId = request.context().tenantId();
        AtomicInteger messageSequence = new AtomicInteger(0);
        List<Map<String, Object>> messages = initializeConversation(request, tenantId, messageSequence);

        // Execute ReAct loop
        return executeReActLoop(
                request, intent, systemPrompt, toolSchemas, metrics,
                startTimeMs, tenantId, messageSequence, messages);
    }

    /**
     * Builds the system prompt with persona, context, and optional workflow context.
     */
    private String buildSystemPrompt(AgentRequest request, CommentIntent intent) {
        SystemPromptBuilder promptBuilder = new SystemPromptBuilder()
                .appendPersona()
                .appendContext(request)
                .appendIntentGuidelines(intent);

        if (workflowContextProvider != null) {
            String tenantId = request.context().tenantId();
            String ticketKey = request.ticketKey();
            promptBuilder.withWorkflowContext(
                    workflowContextProvider.getContextByKey(tenantId, ticketKey));
        }

        return promptBuilder.build();
    }

    /**
     * Gets available tool schemas for the request.
     */
    private List<Map<String, Object>> getToolSchemas(AgentRequest request) {
        List<Tool> tools = guardedToolRegistry != null
                ? guardedToolRegistry.getAvailableTools(request.ticketInfo())
                : List.of();
        return tools.stream()
                .map(Tool::toApiFormat)
                .toList();
    }

    /**
     * Initializes metrics for tracking agent execution.
     */
    private AgentMetrics initializeMetrics(AgentRequest request) {
        return new AgentMetrics()
                .withTenantId(request.context().tenantId())
                .withPlatform(request.platform());
    }

    /**
     * Initializes the conversation with the user message.
     */
    private List<Map<String, Object>> initializeConversation(
            AgentRequest request, String tenantId, AtomicInteger messageSequence) {
        ConversationMessage userMsg = createTextMessage(
                tenantId, request.ticketKey(), "user", request.commentAuthor(), request.commentBody(),
                request.ackCommentId(), messageSequence.getAndIncrement());

        if (windowManager != null) {
            return windowManager.appendAndBuild(tenantId, request.ticketKey(), userMsg);
        } else {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", request.commentBody()));
            return messages;
        }
    }

    /**
     * Executes the ReAct loop - the core reasoning and action cycle.
     */
    private AgentResponse executeReActLoop(
            AgentRequest request, CommentIntent intent,
            String systemPrompt, List<Map<String, Object>> toolSchemas,
            AgentMetrics metrics, long startTimeMs,
            String tenantId, AtomicInteger messageSequence,
            List<Map<String, Object>> messages) {

        List<String> toolsUsed = new ArrayList<>();
        TokenUsage totalUsage = new TokenUsage();
        int totalToolsCount = 0;

        for (int turn = 1; turn <= maxTurns; turn++) {
            // Execute AI call
            AiClient.ToolUseResponse response = executeAiCall(request, systemPrompt, messages, toolSchemas, turn);
            totalUsage.add(TokenUsage.builder()
                    .inputTokens(response.inputTokens())
                    .outputTokens(response.outputTokens())
                    .totalTokens(response.totalTokens())
                    .build());

            // Update conversation with assistant response
            messages = updateConversationWithAssistant(
                    tenantId, request.ticketKey(), response, messageSequence, messages);

            // Check if agent returned final response (no tool calls)
            if (!response.hasToolUse()) {
                return buildFinalResponse(request, response, toolsUsed, totalUsage, turn, metrics, startTimeMs);
            }

            // Execute tools and collect results
            List<Map<String, Object>> toolResultBlocks = executeTools(
                    request, tenantId, messageSequence, messages, toolsUsed, totalToolsCount, response);

            // Track progress if available
            if (progressTracker != null && request.ackCommentId() != null) {
                progressTracker.updateProgress(request.ackCommentId(), List.of());
            }
        }

        // Max turns reached
        return handleMaxTurns(request, toolsUsed, totalUsage, metrics, startTimeMs);
    }

    /**
     * Executes an AI model call.
     */
    private AiClient.ToolUseResponse executeAiCall(
            AgentRequest request, String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> toolSchemas, int turn) {
        try {
            return aiClient.chatWithTools(systemPrompt, messages, toolSchemas);
        } catch (AgentExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "AI model call failed on turn " + turn + " for ticket=" + request.ticketKey(), e);
        }
    }

    /**
     * Updates conversation with assistant's response.
     */
    private List<Map<String, Object>> updateConversationWithAssistant(
            String tenantId, String ticketKey,
            AiClient.ToolUseResponse response,
            AtomicInteger messageSequence,
            List<Map<String, Object>> messages) {
        if (windowManager != null) {
            ConversationMessage assistantMsg = createAssistantMessage(
                    tenantId, ticketKey, response, messageSequence.getAndIncrement());
            return windowManager.appendAndBuild(tenantId, ticketKey, assistantMsg);
        } else if (response.hasToolUse()) {
            messages.add(buildAssistantMessage(response));
            return messages;
        }
        return messages;
    }

    /**
     * Builds the final response when agent returns text (no tools).
     */
    private AgentResponse buildFinalResponse(
            AgentRequest request, AiClient.ToolUseResponse response,
            List<String> toolsUsed, TokenUsage totalUsage,
            int turn, AgentMetrics metrics, long startTimeMs) {
        String finalText = response.getText();
        trackCost(request.ticketKey(), totalUsage.getTotalTokens());

        if (progressTracker != null) {
            progressTracker.complete(request.ackCommentId(), finalText);
        }

        metrics.recordTurns(turn)
                .recordTokens(totalUsage.getTotalTokens())
                .recordTools(toolsUsed.size())
                .recordLatency(System.currentTimeMillis() - startTimeMs)
                .recordErrors(0)
                .flush();

        return new AgentResponse(finalText, toolsUsed, totalUsage.getTotalTokens(), turn);
    }

    /**
     * Handles the case when max turns is reached.
     */
    private AgentResponse handleMaxTurns(
            AgentRequest request, List<String> toolsUsed,
            TokenUsage totalUsage, AgentMetrics metrics, long startTimeMs) {
        log.warn("Agent hit max turns ({}) for ticket={}", maxTurns, request.ticketKey());
        trackCost(request.ticketKey(), totalUsage.getTotalTokens());

        String maxTurnsMsg = "I've reached the maximum number of processing steps (" + maxTurns + ").";
        if (progressTracker != null) {
            progressTracker.fail(request.ackCommentId(), maxTurnsMsg);
        }

        metrics.recordTurns(maxTurns)
                .recordTokens(totalUsage.getTotalTokens())
                .recordTools(toolsUsed.size())
                .recordLatency(System.currentTimeMillis() - startTimeMs)
                .recordErrors(1)
                .flush();

        return new AgentResponse(maxTurnsMsg, toolsUsed, totalUsage.getTotalTokens(), maxTurns);
    }

    /**
     * Executes all tool calls and returns the result blocks.
     */
    private List<Map<String, Object>> executeTools(
            AgentRequest request, String tenantId,
            AtomicInteger messageSequence,
            List<Map<String, Object>> messages,
            List<String> toolsUsed,
            int totalToolsCount,
            AiClient.ToolUseResponse response) {

        List<ToolCall> toolCalls = extractToolCalls(response);
        List<Map<String, Object>> toolResultBlocks = new ArrayList<>();

        for (ToolCall call : toolCalls) {
            toolsUsed.add(call.name());
            log.info("Executing tool: {} (id={})", call.name(), call.id());

            ToolCall sanitizedCall = sanitizeToolCall(call);

            ToolResult result = guardedToolRegistry != null
                    ? guardedToolRegistry.execute(
                            request.context(), sanitizedCall, request.ticketKey(), request.commentAuthor())
                    : ToolResult.error(call.id(), "No tool registry configured");

            toolResultBlocks.add(result.toContentBlock());
        }

        // Add tool results to conversation
        if (windowManager != null) {
            ConversationMessage toolMsg = createToolResultMessage(
                    tenantId, request.ticketKey(), toolResultBlocks, messageSequence.getAndIncrement());
            windowManager.appendAndBuild(tenantId, request.ticketKey(), toolMsg);
        } else {
            messages.add(Map.of("role", "user", "content", toolResultBlocks));
        }

        return toolResultBlocks;
    }

    private ToolCall sanitizeToolCall(ToolCall call) {
        JsonNode input = call.input();
        if (input != null && input.isObject()) {
            ObjectNode sanitized = OBJECT_MAPPER.createObjectNode();
            input.fields().forEachRemaining(entry -> {
                JsonNode val = entry.getValue();
                if (val.isTextual()) {
                    sanitized.put(entry.getKey(), val.asText().trim());
                } else {
                    sanitized.set(entry.getKey(), val);
                }
            });
            return new ToolCall(call.id(), call.name(), sanitized);
        }
        return call;
    }

    private ConversationMessage createTextMessage(
            String tenantId, String ticketKey, String role, String author, String text, String commentId,
            int sequence) {
        List<Map<String, String>> blocks = List.of(Map.of("type", "text", "text", text));
        String contentJson = toJson(blocks);
        Instant now = Instant.now();
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(tenantId, ticketKey))
                .sk(ConversationMessage.createSk(now, sequence))
                .role(role)
                .author(author)
                .commentId(commentId)
                .timestamp(now)
                .ttl(ConversationMessage.defaultTtl())
                .contentJson(contentJson)
                .tokenCount(estimateTokens(contentJson))
                .build();
    }

    private ConversationMessage createAssistantMessage(
            String tenantId, String ticketKey, AiClient.ToolUseResponse response, int sequence) {
        ArrayNode blocks = response.contentBlocks();
        String contentJson = toJson(blocks);
        Instant now = Instant.now();
        int tokens = response.totalTokens() > 0 ? response.outputTokens() : estimateTokens(contentJson);
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(tenantId, ticketKey))
                .sk(ConversationMessage.createSk(now, sequence))
                .role("assistant")
                .author("ai-agent")
                .timestamp(now)
                .ttl(ConversationMessage.defaultTtl())
                .contentJson(contentJson)
                .tokenCount(tokens)
                .build();
    }

    private ConversationMessage createToolResultMessage(
            String tenantId, String ticketKey, List<Map<String, Object>> toolResults, int sequence) {
        String contentJson = toJson(toolResults);
        Instant now = Instant.now();
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(tenantId, ticketKey))
                .sk(ConversationMessage.createSk(now, sequence))
                .role("user")
                .author("tool-output")
                .timestamp(now)
                .ttl(ConversationMessage.defaultTtl())
                .contentJson(contentJson)
                .tokenCount(estimateTokens(contentJson))
                .build();
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty())
            return 0;
        return Math.max(1, text.length() / 4);
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AgentExecutionException("Failed to serialize message content", e);
        }
    }

    private List<ToolCall> extractToolCalls(AiClient.ToolUseResponse response) {
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : response.contentBlocks()) {
            if ("tool_use".equals(block.path("type").asText())) {
                calls.add(new ToolCall(
                        block.get("id").asText(),
                        block.get("name").asText(),
                        block.get("input")));
            }
        }
        return calls;
    }

    private Map<String, Object> buildAssistantMessage(AiClient.ToolUseResponse response) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        for (JsonNode block : response.contentBlocks()) {
            contentBlocks.add(OBJECT_MAPPER.convertValue(block, Map.class));
        }
        return Map.of("role", "assistant", "content", contentBlocks);
    }

    private void trackCost(String ticketKey, int tokens) {
        if (costTracker != null && tokens > 0) {
            costTracker.addTokens(ticketKey, tokens);
        }
    }
}
