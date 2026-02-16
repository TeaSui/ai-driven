package com.aidriven.tool.source;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.model.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceControlToolProviderTest {

    @Mock
    private SourceControlClient sourceControlClient;

    private SourceControlToolProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        provider = new SourceControlToolProvider(sourceControlClient, "main");
        objectMapper = new ObjectMapper();
    }

    // ─── Namespace ───

    @Test
    void should_have_correct_namespace() {
        assertEquals("source_control", provider.namespace());
    }

    // ─── Tool Definitions ───

    @Test
    void should_define_six_tools() {
        List<Tool> tools = provider.toolDefinitions();
        assertEquals(6, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("source_control_get_file")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("source_control_create_pr")));
    }

    // ─── get_file ───

    @Test
    void should_get_file_content() throws Exception {
        when(sourceControlClient.getFileContent("main", "src/Main.java"))
                .thenReturn("public class Main {}");

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "src/Main.java");

        ToolResult result = provider.execute(new ToolCall("call-1", "source_control_get_file", input));

        assertFalse(result.isError());
        assertEquals("public class Main {}", result.content());
    }

    @Test
    void should_return_error_when_file_not_found() throws Exception {
        when(sourceControlClient.getFileContent(anyString(), anyString())).thenReturn(null);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "nonexistent.java");

        ToolResult result = provider.execute(new ToolCall("call-1", "source_control_get_file", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    // ─── search_files ───

    @Test
    void should_search_files() throws Exception {
        when(sourceControlClient.searchFiles("UserService"))
                .thenReturn(List.of("src/main/UserService.java", "src/test/UserServiceTest.java"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "UserService");

        ToolResult result = provider.execute(new ToolCall("call-1", "source_control_search_files", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("Found 2 files"));
        assertTrue(result.content().contains("UserService.java"));
    }

    // ─── create_branch ───

    @Test
    void should_create_branch() throws Exception {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("branch_name", "fix/NPE-123");

        ToolResult result = provider.execute(new ToolCall("call-1", "source_control_create_branch", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("fix/NPE-123"));
        verify(sourceControlClient).createBranch("fix/NPE-123", "main");
    }

    // ─── create_pr ───

    @Test
    void should_create_pull_request() throws Exception {
        when(sourceControlClient.createPullRequest("Fix NPE", "Fixes null check", "fix/NPE-123", "main"))
                .thenReturn(new SourceControlClient.PullRequestResult("42", "https://github.com/pr/42", "fix/NPE-123"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("title", "Fix NPE");
        input.put("description", "Fixes null check");
        input.put("source_branch", "fix/NPE-123");

        ToolResult result = provider.execute(new ToolCall("call-1", "source_control_create_pr", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("https://github.com/pr/42"));
    }

    // ─── Unknown Action ───

    @Test
    void should_return_error_for_unknown_action() {
        ObjectNode input = objectMapper.createObjectNode();
        ToolResult result = provider.execute(new ToolCall("call-1", "source_control_unknown", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown action"));
    }

    // ─── Error Handling ───

    @Test
    void should_handle_client_exception() throws Exception {
        when(sourceControlClient.getFileContent(anyString(), anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "src/Main.java");

        ToolResult result = provider.execute(new ToolCall("call-1", "source_control_get_file", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Connection refused"));
    }
}
