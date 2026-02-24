package com.aidriven.tool.source;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.provider.SourceControlProvider;
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
    private OperationContext operationContext;

    @BeforeEach
    void setUp() {
        provider = new SourceControlToolProvider(sourceControlClient, "main");
        objectMapper = new ObjectMapper();
        operationContext = OperationContext.builder().tenantId("test-tenant").build();
    }

    // ─── Namespace ───

    @Test
    void should_have_correct_namespace() {
        assertEquals("source_control", provider.namespace());
    }

    // ─── Tool Definitions ───

    @Test
    void should_define_ten_tools() {
        List<Tool> tools = provider.toolDefinitions();
        assertEquals(10, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("source_control_get_file")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("source_control_create_pr")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("source_control_get_ci_logs")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("source_control_view_file_outline")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("source_control_search_grep")));
    }

    // ─── get_file ───

    @Test
    void should_get_file_content() throws Exception {
        when(sourceControlClient.getFileContent(eq(operationContext), eq(BranchName.of("main")), eq("src/Main.java")))
                .thenReturn("public class Main {}");

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "src/Main.java");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_get_file", input));

        assertFalse(result.isError());
        assertEquals("public class Main {}", result.content());
    }

    @Test
    void should_return_error_when_file_not_found() throws Exception {
        when(sourceControlClient.getFileContent(any(), any(BranchName.class), anyString())).thenReturn(null);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "nonexistent.java");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_get_file", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    // ─── search_files ───

    @Test
    void should_search_files() throws Exception {
        when(sourceControlClient.searchFiles(eq(operationContext), eq("UserService")))
                .thenReturn(List.of("src/main/UserService.java", "src/test/UserServiceTest.java"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "UserService");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_search_files", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("Found 2 files"));
        assertTrue(result.content().contains("UserService.java"));
    }

    // ─── create_branch ───

    @Test
    void should_create_branch() throws Exception {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("branch_name", "fix/NPE-123");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_create_branch", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("fix/NPE-123"));
        verify(sourceControlClient).createBranch(operationContext, BranchName.of("fix/NPE-123"), BranchName.of("main"));
    }

    // ─── create_pr ───

    @Test
    void should_create_pull_request() throws Exception {
        when(sourceControlClient.createPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(new SourceControlProvider.PullRequestResult("1", "url", BranchName.of("src"), "title"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("title", "Fix NPE");
        input.put("description", "Fixes null check");
        input.put("source_branch", "fix/NPE-123");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_create_pr", input));

        assertEquals("PR: url", result.content());
    }

    // ─── Unknown Action ───

    @Test
    void should_return_error_for_unknown_action() {
        ObjectNode input = objectMapper.createObjectNode();
        ToolResult result = provider.execute(operationContext, new ToolCall("call-1", "source_control_unknown", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown action"));
    }

    // ─── Error Handling ───

    @Test
    void should_handle_client_exception() throws Exception {
        when(sourceControlClient.getFileContent(any(), any(BranchName.class), anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "src/Main.java");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_get_file", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Connection refused"));
    }

    // ─── view_file_outline ───

    @Test
    void view_file_outline_returns_ast_skeleton_for_java_file() throws Exception {
        String javaCode = """
                public class UserService {
                    public void createUser(String name) {}
                    private String findById(Long id) { return null; }
                }
                """;
        when(sourceControlClient.getFileContent(eq(operationContext), eq(BranchName.of("main")),
                eq("src/UserService.java")))
                .thenReturn(javaCode);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "src/UserService.java");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_view_file_outline", input));

        assertFalse(result.isError());
        String content = result.content();
        assertTrue(content.contains("UserService"), "Should contain class name");
        assertTrue(content.contains("createUser"), "Should contain method signature");
        assertTrue(content.contains("findById"), "Should contain private method");
        assertFalse(content.contains("return null"), "Should NOT include method body");
    }

    @Test
    void view_file_outline_falls_back_to_truncated_raw_text_for_non_java() throws Exception {
        String jsCode = "const x = () => 42;";
        when(sourceControlClient.getFileContent(eq(operationContext), eq(BranchName.of("main")),
                eq("script.js")))
                .thenReturn(jsCode);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "script.js");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_view_file_outline", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains(jsCode), "Should contain the raw text fallback");
    }

    @Test
    void view_file_outline_returns_error_when_file_not_found() throws Exception {
        when(sourceControlClient.getFileContent(any(), any(), anyString())).thenReturn(null);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("file_path", "missing.java");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_view_file_outline", input));

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    // ─── search_grep ───

    @Test
    void search_grep_returns_matching_lines_from_file() throws Exception {
        String fileContent = "class Foo {\n  String bar = \"hello\";\n  String baz = \"world\";\n}";
        when(sourceControlClient.getFileContent(eq(operationContext), eq(BranchName.of("main")),
                eq("Foo.java")))
                .thenReturn(fileContent);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "bar");
        input.put("file_path", "Foo.java");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_search_grep", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("Foo.java:2"), "Should include file:line reference");
        assertTrue(result.content().contains("bar"));
        assertFalse(result.content().contains("baz"), "Should only return matching lines");
    }

    @Test
    void search_grep_returns_no_matches_message_when_nothing_found() throws Exception {
        when(sourceControlClient.getFileContent(any(), any(), anyString())).thenReturn("some content");

        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "xyz_no_match");
        input.put("file_path", "Foo.java");

        ToolResult result = provider.execute(operationContext,
                new ToolCall("call-1", "source_control_search_grep", input));

        assertFalse(result.isError());
        assertTrue(result.content().contains("No matches found"));
    }
}
