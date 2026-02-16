package com.aidriven.core.agent;

import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
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
 * <p>Phase 3: Intent-aware prompting and guardrailed tool execution.</p>
 */
@Slf4j
public class AgentOrchestrator {

    private static final int DEFAULT_MAX_TURNS = 10;

    private final AiClient aiClient;
    private final ToolRegistry toolRegistry;
    private final GuardedToolRegistry guardedToolRegistry; // nullable (Phase 3+)
    private final ProgressTracker progressTracker;
    private final ConversationWindowManager windowManager;
    private final CostTracker costTracker; // nullable (Phase 3+)
    private final ObjectMapper objectMapper;
    private final int maxTurns;

    /** Phase 1-2 constructor (no guardrails, no cost tracking). */
    public AgentOrchestrator(AiClient aiClient, ToolRegistry toolRegistry, ProgressTracker progressTracker,
            ConversationWindowManager windowManager) {
        this(aiClient, toolRegistry, null, progressTracker, windowManager, null, DEFAULT_MAX_TURNS);
    }

    /** Phase 1-2 constructor with custom max turns. */
    public AgentOrchestrator(AiClient aiClient, ToolRegistry toolRegistry, ProgressTracker progressTracker,
            int maxTurns) {
        this(aiClient, toolRegistry, null, progressTracker, null, null, maxTurns);
    }

    /** Phase 1-2 constructor with window manager and custom max turns. */
    public AgentOrchestrator(AiClient aiClient, ToolRegistry toolRegistry, ProgressTracker progressTracker,
            ConversationWindowManager windowManager, int maxTurns) {
        this(aiClient, toolRegistry, null, progressTracker, windowManager, null, maxTurns);
    }

    /** Phase 3+ full constructor with guardrails and cost tracking. */
    public AgentOrchestrator(AiClient aiClient, ToolRegistry toolRegistry,
            GuardedToolRegistry guardedToolRegistry, ProgressTracker progressTracker,
            ConversationWindowManager windowManager, CostTracker costTracker, int maxTurns) {
        this.aiClient = aiClient;
        this.toolRegistry = toolRegistry;
        this.guardedToolRegistry = guardedToolRegistry;
        this.progressTracker = progressTracker;
        this.windowManager = windowManager;
        this.costTracker = costTracker;
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
        return process(request, CommentIntent.AI_COMMAND);
    }

    /**
     * Process an agent request with intent-aware prompting.
     *
     * @param request Agent request with ticket context and user message
     * @param intent  Classified intent of the comment
     * @return Agent response with final text and metadata
     */
    public AgentResponse process(AgentRequest request, CommentIntent intent) throws Exception {
        // Cost budget check
        if (costTracker != null && !costTracker.hasRemainingBudget(request.ticketKey())) {
            return new AgentResponse(
                    "This ticket has exceeded its token budget. "
                            + "Please create a new ticket or contact an admin to increase the limit.",
                    List.of(), 0, 0);
        }

        String systemPrompt = buildSystemPrompt(request, intent);

        // Get available tools for this ticket
        List<Tool> tools = guardedToolRegistry != null
                ? guardedToolRegistry.getAvailableTools(request.ticketInfo())
                : toolRegistry.getAvailableTools(request.ticketInfo());

        List<Map<String, Object>> toolSchemas = tools.stream()
                .map(Tool::toApiFormat)
                .toList();

        log.info("Agent processing ticket={} intent={} with {} tools, max {} turns",
                request.ticketKey(), intent, tools.size(), maxTurns);

        // Sequence counter prevents DynamoDB sort key collisions
        AtomicInteger messageSequence = new AtomicInteger(0);

        // 1. Persist User Message (from Webhook) and build initial context
        ConversationMessage userMsg = createTextMessage(
                request.ticketKey(), "user", request.commentAuthor(), request.commentBody(),
                request.ackCommentId(), messageSequence.getAndIncrement());

        List<Map<String, Object>> messages;
        if (windowManager != null) {
            messages = windowManager.appendAndBuild(request.ticketKey(), userMsg);
        } else {
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

            AiClient.ToolUseResponse response = aiClient.chatWithTools(
                    systemPrompt, messages, toolSchemas);

            totalTokens += response.totalTokens();

            // 2. Persist Assistant Response
            if (windowManager != null) {
                ConversationMessage assistantMsg = createAssistantMessage(
                        request.ticketKey(), response, messageSequence.getAndIncrement());
                messages = windowManager.appendAndBuild(request.ticketKey(), assistantMsg);
            } else {
                if (response.hasToolUse()) {
                    messages.add(buildAssistantMessage(response));
                }
            }

            if (!response.hasToolUse()) {
                String finalText = response.getText();
                log.info("Agent completed in {} turns, {} tokens, {} tools used",
                        turn, totalTokens, toolsUsed.size());
                trackCost(request.ticketKey(), totalTokens);
                return new AgentResponse(finalText, toolsUsed, totalTokens, turn);
            }

            // Claude wants to use tools — extract and execute
            List<ToolCall> toolCalls = extractToolCalls(response);
            log.info("Claude requested {} tool call(s): {}", toolCalls.size(),
                    toolCalls.stream().map(ToolCall::name).collect(Collectors.joining(", ")));

            List<Map<String, Object>> toolResultBlocks = new ArrayList<>();
            List<ToolResult> results = new ArrayList<>();

            for (ToolCall call : toolCalls) {
                toolsUsed.add(call.name());
                log.info("Executing tool: {} with id: {}", call.name(), call.id());

                // Use guarded registry if available (Phase 3+), otherwise direct
                ToolResult result = guardedToolRegistry != null
                        ? guardedToolRegistry.execute(call, request.ticketKey(), request.commentAuthor())
                        : toolRegistry.execute(call);

                results.add(result);
                Map<String, Object> contentBlock = result.toContentBlock();
                log.info("Tool {} → {}", call.name(), result.isError() ? "ERROR" : "OK");
                toolResultBlocks.add(contentBlock);
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
                messages.add(Map.of("role", "user", "content", toolResultBlocks));
            }
        }

        // Max turns reached
        log.warn("Agent hit max turns ({}) for ticket={}", maxTurns, request.ticketKey());
        trackCost(request.ticketKey(), totalTokens);
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

    Map<String, Object> buildAssistantMessage(AiClient.ToolUseResponse response) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        for (JsonNode block : response.contentBlocks()) {
            contentBlocks.add(objectMapper.convertValue(block, Map.class));
        }
        return Map.of("role", "assistant", "content", contentBlocks);
    }

    /**
     * Builds the system prompt with ticket context and intent-specific instructions.
     */
    String buildSystemPrompt(AgentRequest request, CommentIntent intent) {
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

        // Intent-specific instructions
        switch (intent) {
            case HUMAN_FEEDBACK -> {
                sb.append("## Intent: Feedback on Previous Work\n");
                sb.append("The user is providing feedback on your previous actions.\n");
                sb.append("1. Review the conversation history to understand what you did previously.\n");
                sb.append("2. Apply the feedback by modifying the relevant artifacts (PR, code, etc).\n");
                sb.append("3. Summarize what you changed and why.\n\n");
            }
            case QUESTION -> {
                sb.append("## Intent: Question\n");
                sb.append("The user is asking a question about the ticket, your work, or the codebase.\n");
                sb.append("1. Use tools if needed to gather information.\n");
                sb.append("2. Provide a clear, concise answer.\n");
                sb.append("3. If the question is ambiguous, ask for clarification.\n\n");
            }
            case APPROVAL -> {
                sb.append("## Intent: Approval / Rejection\n");
                sb.append("The user is responding to an approval request for a high-risk action.\n");
                sb.append("The orchestrator will handle the actual approval execution.\n");
                sb.append("Acknowledge the user's decision.\n\n");
            }
            default -> {
                sb.append("## Guidelines\n");
                sb.append("1. Use the available tools to investigate and act on the request.\n");
                sb.append("2. Be precise and concise in your responses.\n");
                sb.append("3. If you need to make code changes, explain your reasoning first.\n");
                sb.append("4. If you're unsure, ask clarifying questions rather than guessing.\n");
                sb.append("5. Always provide actionable results.\n");
                sb.append("6. For destructive operations (merge, delete), the system will request ");
                sb.append("explicit approval from the user before executing.\n");
            }
        }

        return sb.toString();
    }

    /** Backward-compatible overload for Phase 1-2 code. */
    String buildSystemPrompt(AgentRequest request) {
        return buildSystemPrompt(request, CommentIntent.AI_COMMAND);
    }

    private void trackCost(String ticketKey, int tokens) {
        if (costTracker != null && tokens > 0) {
            costTracker.addTokens(ticketKey, tokens);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
