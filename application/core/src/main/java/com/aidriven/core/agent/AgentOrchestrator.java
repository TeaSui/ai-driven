package com.aidriven.core.agent;

import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.ConversationMessage;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The core ReAct (Reason + Act) loop for the AI agent.
 *
 * <p>
 * Flow: Build prompt → Call Claude with tools → Execute tool calls → Repeat
 * until Claude responds with text only (end_turn) or max turns reached.
 * </p>
 *
 * <p>
 * Designed for single Lambda invocation. For Phase 2+, a wall-clock circuit
 * breaker will checkpoint state mid-loop.
 * </p>
 */
@Slf4j
public class AgentOrchestrator {

    private static final int DEFAULT_MAX_TURNS = 10;

    private final AiClient aiClient;
    private final ToolRegistry toolRegistry;
    private final ProgressTracker progressTracker;
    private final ConversationWindowManager windowManager;
    private final ObjectMapper objectMapper;
    private final int maxTurns;

    public AgentOrchestrator(AiClient aiClient, ToolRegistry toolRegistry, ProgressTracker progressTracker,
            ConversationWindowManager windowManager) {
        this(aiClient, toolRegistry, progressTracker, windowManager, DEFAULT_MAX_TURNS);
    }

    public AgentOrchestrator(AiClient aiClient, ToolRegistry toolRegistry, ProgressTracker progressTracker,
            int maxTurns) {
        this(aiClient, toolRegistry, progressTracker, null, maxTurns);
    }

    public AgentOrchestrator(AiClient aiClient, ToolRegistry toolRegistry, ProgressTracker progressTracker,
            ConversationWindowManager windowManager, int maxTurns) {
        this.aiClient = aiClient;
        this.toolRegistry = toolRegistry;
        this.progressTracker = progressTracker;
        this.windowManager = windowManager;
        this.objectMapper = new ObjectMapper();
        this.maxTurns = maxTurns;
    }

    /**
     * Process an agent request through the ReAct loop.
     *
     * @param request Agent request with ticket context and user message
     * @return Agent response with final text and metadata
     */
    public AgentResponse process(AgentRequest request) throws Exception {
        String systemPrompt = buildSystemPrompt(request);

        // Get available tools for this ticket
        List<Tool> tools = toolRegistry.getAvailableTools(request.ticketInfo());
        List<Map<String, Object>> toolSchemas = tools.stream()
                .map(Tool::toApiFormat)
                .toList();

        log.info("Agent processing ticket={} with {} tools, max {} turns",
                request.ticketKey(), tools.size(), maxTurns);

        // Sequence counter prevents DynamoDB sort key collisions
        // when multiple messages are created within the same millisecond.
        AtomicInteger messageSequence = new AtomicInteger(0);

        // 1. Persist User Message (from Webhook) and build initial context
        ConversationMessage userMsg = createTextMessage(
                request.ticketKey(), "user", request.commentAuthor(), request.commentBody(),
                request.ackCommentId(), messageSequence.getAndIncrement());

        List<Map<String, Object>> messages;
        if (windowManager != null) {
            messages = windowManager.appendAndBuild(request.ticketKey(), userMsg);
        } else {
            // Fallback for strict unit tests not using the manager
            messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", request.commentBody()));
        }

        List<String> toolsUsed = new ArrayList<>();
        int totalTokens = 0;
        int turn = 0;

        while (turn < maxTurns) {
            turn++;
            log.info("Agent turn {}/{} for ticket={}, messageCount={}", turn, maxTurns, request.ticketKey(),
                    messages.size());

            // Log the full messages array for debugging
            try {
                log.info("Turn {} messages: {}", turn, objectMapper.writeValueAsString(messages));
            } catch (Exception e) {
                log.warn("Failed to serialize messages for logging", e);
            }

            AiClient.ToolUseResponse response = aiClient.chatWithTools(
                    systemPrompt, messages, toolSchemas);

            totalTokens += response.totalTokens();

            // 2. Persist Assistant Response
            if (windowManager != null) {
                ConversationMessage assistantMsg = createAssistantMessage(
                        request.ticketKey(), response, messageSequence.getAndIncrement());
                messages = windowManager.appendAndBuild(request.ticketKey(), assistantMsg);
            } else {
                // Fallback
                if (response.hasToolUse()) {
                    messages.add(buildAssistantMessage(response));
                }
            }

            if (!response.hasToolUse()) {
                // Claude responded with text only — we're done
                String finalText = response.getText();
                log.info("Agent completed in {} turns, {} tokens, {} tools used",
                        turn, totalTokens, toolsUsed.size());
                return new AgentResponse(finalText, toolsUsed, totalTokens, turn);
            }

            // Claude wants to use tools — extract tool calls and execute them
            List<ToolCall> toolCalls = extractToolCalls(response);
            log.info("Claude requested {} tool call(s): {}", toolCalls.size(),
                    toolCalls.stream().map(ToolCall::name).collect(Collectors.joining(", ")));

            // Execute each tool call and collect results
            List<Map<String, Object>> toolResultBlocks = new ArrayList<>();
            List<ToolResult> results = new ArrayList<>();

            for (ToolCall call : toolCalls) {
                toolsUsed.add(call.name());
                log.info("Executing tool: {} with id: {}", call.name(), call.id());
                ToolResult result = toolRegistry.execute(call);
                results.add(result);
                Map<String, Object> contentBlock = result.toContentBlock();
                log.info("Tool result block: type={}, tool_use_id={}, contentLen={}, isError={}",
                        contentBlock.get("type"), contentBlock.get("tool_use_id"),
                        result.content() != null ? result.content().length() : 0, result.isError());
                toolResultBlocks.add(contentBlock);
                log.info("Tool {} → {}", call.name(), result.isError() ? "ERROR" : "OK");
            }

            // Update progress
            if (request.ackCommentId() != null) {
                progressTracker.updateProgress(request.ackCommentId(), results);
            }

            // 3. Persist Tool Results
            if (windowManager != null) {
                ConversationMessage toolMsg = createToolResultMessage(
                        request.ticketKey(), toolResultBlocks, messageSequence.getAndIncrement());
                messages = windowManager.appendAndBuild(request.ticketKey(), toolMsg);
            } else {
                // Fallback
                messages.add(Map.of("role", "user", "content", toolResultBlocks));
            }
        }

        // Max turns reached
        log.warn("Agent hit max turns ({}) for ticket={}", maxTurns, request.ticketKey());
        return new AgentResponse(
                "I've reached the maximum number of processing steps (" + maxTurns + "). "
                        + "Here's what I've done so far with the tools: "
                        + String.join(", ", toolsUsed) + ". "
                        + "Please provide additional guidance if needed.",
                toolsUsed, totalTokens, maxTurns);
    }

    // --- Message Factories ---

    private ConversationMessage createTextMessage(
            String ticketKey, String role, String author, String text, String commentId, int sequence) {

        // Wrap text in content block
        List<Map<String, String>> blocks = List.of(Map.of("type", "text", "text", text));
        String contentJson = toJson(blocks);

        Instant now = Instant.now();
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(ticketKey))
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
            String ticketKey, AiClient.ToolUseResponse response, int sequence) {

        ArrayNode blocks = response.contentBlocks();
        String contentJson = toJson(blocks);
        Instant now = Instant.now();

        // Use actual token count from Claude response when available
        int tokens = response.totalTokens() > 0 ? response.outputTokens() : estimateTokens(contentJson);

        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(ticketKey))
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
            String ticketKey, List<Map<String, Object>> toolResults, int sequence) {

        String contentJson = toJson(toolResults);
        Instant now = Instant.now();
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(ticketKey))
                .sk(ConversationMessage.createSk(now, sequence))
                .role("user")
                .author("tool-output")
                .timestamp(now)
                .ttl(ConversationMessage.defaultTtl())
                .contentJson(contentJson)
                .tokenCount(estimateTokens(contentJson))
                .build();
    }

    /**
     * Estimates token count from text content.
     * Uses ~4 chars per token heuristic (conservative for English + code).
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize message content", e);
        }
    }

    /** Extracts ToolCall records from Claude's response content blocks. */
    List<ToolCall> extractToolCalls(AiClient.ToolUseResponse response) {
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

    /**
     * Builds the assistant message from Claude's response (preserving tool_use
     * blocks).
     */
    Map<String, Object> buildAssistantMessage(AiClient.ToolUseResponse response) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        for (JsonNode block : response.contentBlocks()) {
            contentBlocks.add(objectMapper.convertValue(block, Map.class));
        }
        return Map.of("role", "assistant", "content", contentBlocks);
    }

    /** Builds the system prompt with ticket context. */
    String buildSystemPrompt(AgentRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI development assistant integrated with Jira. ");
        sb.append("You help developers investigate issues, analyze code, and make changes.\n\n");

        sb.append("## Current Context\n");
        sb.append("- Ticket: ").append(request.ticketKey()).append("\n");
        if (request.ticketInfo() != null) {
            sb.append("- Title: ").append(request.ticketInfo().getSummary()).append("\n");
            if (request.ticketInfo().getDescription() != null) {
                sb.append("- Description: ").append(
                        truncate(request.ticketInfo().getDescription(), 2000)).append("\n");
            }
        }
        sb.append("- Requested by: ").append(request.commentAuthor()).append("\n\n");

        sb.append("## Guidelines\n");
        sb.append("1. Use the available tools to investigate and act on the request.\n");
        sb.append("2. Be precise and concise in your responses.\n");
        sb.append("3. If you need to make code changes, explain your reasoning first.\n");
        sb.append("4. If you're unsure, ask clarifying questions rather than guessing.\n");
        sb.append("5. Always provide actionable results.\n");

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
