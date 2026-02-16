package com.aidriven.core.agent;

import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.tool.*;
import com.aidriven.core.agent.tool.Schema;
import com.aidriven.core.model.TicketInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    private AiClient aiClient;
    @Mock
    private ProgressTracker progressTracker;
    @Mock
    private ConversationWindowManager windowManager;

    private ToolRegistry toolRegistry;
    private AgentOrchestrator orchestrator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        objectMapper = new ObjectMapper();
        orchestrator = new AgentOrchestrator(aiClient, toolRegistry, progressTracker, windowManager, 5);
    }

    // ─── Single-Turn (No Tool Use) ───

    @Test
    void should_return_text_when_no_tools_used() throws Exception {
        // Claude responds with text only, stop_reason=end_turn
        AiClient.ToolUseResponse response = buildTextResponse("Here is my analysis.", "end_turn");
        when(aiClient.chatWithTools(anyString(), anyList(), anyList())).thenReturn(response);
        // Mock window manager to return just the current message provided
        when(windowManager.appendAndBuild(anyString(), any())).thenAnswer(inv -> {
            // Return simplified list for test
            return List.of(Map.of("role", "user", "content", "analyze this code"));
        });

        AgentRequest request = new AgentRequest("PROJ-1", "analyze this code", "Dev", null, "comment-123");
        AgentResponse result = orchestrator.process(request);

        assertEquals("Here is my analysis.", result.text());
        assertEquals(1, result.turnCount());
        assertTrue(result.toolsUsed().isEmpty());
    }

    @Test
    void should_persist_conversation_history() throws Exception {
        // Arrange
        AiClient.ToolUseResponse response = buildTextResponse("Done.", "end_turn");
        when(aiClient.chatWithTools(anyString(), anyList(), anyList())).thenReturn(response);

        // Capture what happens when appendAndBuild is called
        when(windowManager.appendAndBuild(eq("PROJ-1"), any())).thenReturn(List.of(
                Map.of("role", "user", "content", "msg1"),
                Map.of("role", "assistant", "content", "msg2")));

        // Act
        AgentRequest request = new AgentRequest("PROJ-1", "user msg", "Dev", null, "comment-123");
        orchestrator.process(request);

        // Assert
        // 1. Appends user message
        verify(windowManager).appendAndBuild(eq("PROJ-1"),
                argThat(msg -> "user".equals(msg.getRole()) && msg.getContentJson().contains("user msg")));

        // 2. Appends assistant message
        verify(windowManager).appendAndBuild(eq("PROJ-1"),
                argThat(msg -> "assistant".equals(msg.getRole()) && msg.getContentJson().contains("Done.")));
    }

    // ─── Multi-Turn Tool Use ───

    @Test
    void should_execute_tool_and_loop() throws Exception {
        // Turn 1: Claude requests a tool
        AiClient.ToolUseResponse toolResponse = buildToolUseResponse(
                "toolu_1", "test_tool_get_data", objectMapper.createObjectNode());

        // Turn 2: Claude responds with text after seeing tool result
        AiClient.ToolUseResponse textResponse = buildTextResponse("Based on the data, the issue is...", "end_turn");

        when(aiClient.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(toolResponse)
                .thenReturn(textResponse);

        // Mock window manager
        when(windowManager.appendAndBuild(anyString(), any())).thenReturn(List.of(
                Map.of("role", "user", "content", "check data")));

        // Register a stub provider
        toolRegistry.register(new ToolProvider() {
            public String namespace() {
                return "test_tool";
            }

            public List<Tool> toolDefinitions() {
                return List.of(
                        Tool.of("test_tool_get_data", "Get data", Schema.object()));
            }

            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), "data: 42");
            }
        });

        AgentRequest request = new AgentRequest("PROJ-1", "check the data", "Dev", null, "comment-123");
        AgentResponse result = orchestrator.process(request);

        assertEquals("Based on the data, the issue is...", result.text());
        assertEquals(2, result.turnCount());
        assertEquals(List.of("test_tool_get_data"), result.toolsUsed());
        verify(progressTracker, times(1)).updateProgress(eq("comment-123"), anyList());
    }

    // ─── Max Turns Circuit Breaker ───

    @Test
    void should_stop_at_max_turns() throws Exception {
        // Claude always requests tools — should stop at maxTurns
        AiClient.ToolUseResponse toolResponse = buildToolUseResponse(
                "toolu_1", "test_tool_get_data", objectMapper.createObjectNode());

        when(aiClient.chatWithTools(anyString(), anyList(), anyList())).thenReturn(toolResponse);
        when(windowManager.appendAndBuild(anyString(), any())).thenReturn(List.of());

        toolRegistry.register(new ToolProvider() {
            public String namespace() {
                return "test_tool";
            }

            public List<Tool> toolDefinitions() {
                return List.of(
                        Tool.of("test_tool_get_data", "Get data", Schema.object()));
            }

            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), "data");
            }
        });

        AgentRequest request = new AgentRequest("PROJ-1", "infinite loop", "Dev", null, "comment-123");
        AgentResponse result = orchestrator.process(request);

        assertEquals(5, result.turnCount()); // maxTurns = 5
        assertTrue(result.text().contains("maximum number of processing steps"));
    }

    // ─── System Prompt ───

    @Test
    void should_build_system_prompt_with_ticket_context() {
        TicketInfo ticket = new TicketInfo();
        ticket.setTicketKey("PROJ-42");
        ticket.setSummary("Fix NPE in UserService");
        ticket.setDescription("NullPointerException when user is null");

        AgentRequest request = new AgentRequest("PROJ-42", "fix this", "Dev", ticket, "comment-123");
        String prompt = orchestrator.buildSystemPrompt(request);

        assertTrue(prompt.contains("PROJ-42"));
        assertTrue(prompt.contains("Fix NPE in UserService"));
        assertTrue(prompt.contains("NullPointerException"));
        assertTrue(prompt.contains("Dev"));
    }

    // ─── Tool Call Parsing ───

    @Test
    void should_extract_tool_calls_from_response() {
        AiClient.ToolUseResponse response = buildToolUseResponse(
                "toolu_1", "source_control_get_file",
                objectMapper.createObjectNode().put("file_path", "src/Main.java"));

        List<ToolCall> calls = orchestrator.extractToolCalls(response);

        assertEquals(1, calls.size());
        assertEquals("toolu_1", calls.get(0).id());
        assertEquals("source_control_get_file", calls.get(0).name());
        assertEquals("src/Main.java", calls.get(0).input().get("file_path").asText());
    }

    // ─── Helpers ───

    private AiClient.ToolUseResponse buildTextResponse(String text, String stopReason) {
        ArrayNode blocks = objectMapper.createArrayNode();
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        blocks.add(textBlock);
        return new AiClient.ToolUseResponse(blocks, stopReason, 100, 50);
    }

    private AiClient.ToolUseResponse buildToolUseResponse(String id, String name, ObjectNode input) {
        ArrayNode blocks = objectMapper.createArrayNode();

        // Optional thinking text
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "Let me check that...");
        blocks.add(textBlock);

        // Tool use block
        ObjectNode toolBlock = objectMapper.createObjectNode();
        toolBlock.put("type", "tool_use");
        toolBlock.put("id", id);
        toolBlock.put("name", name);
        toolBlock.set("input", input);
        blocks.add(toolBlock);

        return new AiClient.ToolUseResponse(blocks, "tool_use", 200, 100);
    }
}
