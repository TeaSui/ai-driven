package com.aidriven.lambda;

import com.aidriven.core.config.FetchConfig;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.tool.context.ContextService;
import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CodeFetchHandlerTest {

    @Mock
    private FetchConfig config;
    @Mock
    private TicketStateRepository ticketStateRepository;

    @Mock
    private ContextStorageService contextStorageService;

    @Mock
    private SourceControlClient sourceControlClient;

    @Mock
    private Context lambdaContext;

    @Mock
    private ContextService contextService;

    @Mock
    private ServiceFactory serviceFactory;

    @TempDir
    Path tempDir;

    private CodeFetchHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Default config behavior
        when(config.maxTotalContextChars()).thenReturn(1000L);
        when(config.maxFileSizeBytes()).thenReturn(1000L);
        when(config.maxFileSizeChars()).thenReturn(100);
        when(config.contextMode()).thenReturn("FULL_REPO"); // Changed default to FULL_REPO for consistency with the
                                                            // provided snippet

        // Mock factory to return our mock context service
        when(serviceFactory.createContextService(any())).thenReturn(contextService);

        // Pass mock client and factory in constructor
        handler = new CodeFetchHandler(config, ticketStateRepository, contextStorageService,
                sourceControlClient, serviceFactory);
    }

    // ======== Input Validation ========

    @Test
    void should_throw_for_null_input() {
        assertThrows(NullPointerException.class,
                () -> handler.handleRequest(null, lambdaContext));
    }

    // ======== Platform Pass-Through (impl-09) ========

    @Test
    void should_pass_through_platform_in_output() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();
        input.put("platform", "GITHUB");

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("GITHUB", result.get("platform"));
    }

    @Test
    void should_pass_through_bitbucket_platform() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();
        input.put("platform", "BITBUCKET");

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("BITBUCKET", result.get("platform"));
    }

    @Test
    void should_not_include_platform_when_absent_in_input() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();
        // No platform in input

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertFalse(result.containsKey("platform"));
    }

    // ======== Repository Pass-Through (impl-10) ========

    @Test
    void should_pass_through_repo_info_in_output() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();
        input.put("repoOwner", "my-workspace");
        input.put("repoSlug", "my-repo");

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("my-workspace", result.get("repoOwner"));
        assertEquals("my-repo", result.get("repoSlug"));
    }

    @Test
    void should_not_include_repo_info_when_absent() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();
        // No repo info in input

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertFalse(result.containsKey("repoOwner"));
        assertFalse(result.containsKey("repoSlug"));
    }

    // ======== Core Functionality ========

    @Test
    void should_fetch_code_and_store_in_s3() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("context/PROJ-1/123.txt");

        Map<String, Object> input = buildValidInput();

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("12345", result.get("ticketId"));
        assertEquals("PROJ-1", result.get("ticketKey"));
        assertEquals("context/PROJ-1/123.txt", result.get("codeContextS3Key"));
        assertTrue((int) result.get("fileCount") > 0);
        verify(contextStorageService).storeContext(eq("PROJ-1"), anyString());
        verify(ticketStateRepository).save(any());
    }

    @Test
    void should_use_incremental_context_when_configured() throws Exception {
        // Re-init handler with INCREMENTAL mode
        FetchConfig config = new FetchConfig(100000, 3000000L, 500000L, "INCREMENTAL");
        handler = new CodeFetchHandler(
                config,
                ticketStateRepository, contextStorageService, sourceControlClient, serviceFactory);

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(contextService.buildContext(any(OperationContext.class), any(TicketInfo.class), any(BranchName.class)))
                .thenReturn("content");
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("incremental-key");

        Map<String, Object> input = buildValidInput();
        input.put("resolvedModel", "claude-haiku-4-5");

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("INCREMENTAL", result.get("contextMode"));
        assertEquals("incremental-key", result.get("codeContextS3Key"));
        assertEquals("claude-haiku-4-5", result.get("resolvedModel"));

        // Verify full repo download was NOT called
        verify(sourceControlClient, never()).downloadArchive(any(), any(BranchName.class), any());
    }

    @Test
    void should_fallback_to_full_repo_when_incremental_fails() throws Exception {
        // Re-init handler with INCREMENTAL mode
        FetchConfig config = new FetchConfig(100000, 3000000L, 500000L, "INCREMENTAL");
        handler = new CodeFetchHandler(
                config,
                ticketStateRepository, contextStorageService, sourceControlClient, serviceFactory);

        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any(OperationContext.class))).thenReturn(BranchName.of("main"));
        when(contextService.buildContext(any(OperationContext.class), any(TicketInfo.class), eq(BranchName.of("main"))))
                .thenReturn(null);
        when(sourceControlClient.downloadArchive(any(OperationContext.class), eq(BranchName.of("main")),
                any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("full-repo-key");

        Map<String, Object> input = buildValidInput();

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("FULL_REPO", result.get("contextMode"));
        assertEquals("full-repo-key", result.get("codeContextS3Key"));

        // Verify full repo download WAS called
        verify(sourceControlClient).downloadArchive(any(), eq(BranchName.of("main")), any());
    }

    @Test
    void should_pass_through_resolved_model_in_full_repo_mode() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();
        input.put("resolvedModel", "claude-sonnet-4-5");

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("claude-sonnet-4-5", result.get("resolvedModel"));
    }

    @Test
    void should_handle_source_control_error_gracefully() throws Exception {
        when(sourceControlClient.getDefaultBranch(any())).thenThrow(new RuntimeException("Connection refused"));

        Map<String, Object> input = buildValidInput();

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("12345", result.get("ticketId"));
        assertEquals("PROJ-1", result.get("ticketKey"));
        assertEquals("", result.get("codeContextS3Key"));
        assertNotNull(result.get("fetchError"));
    }

    @Test
    void should_pass_through_input_fields() throws Exception {
        Path repoDir = createMockRepo();

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();
        input.put("summary", "Fix login");
        input.put("description", "Login broken");
        input.put("labels", List.of("ai-generate"));
        input.put("priority", "High");
        input.put("dryRun", true);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("Fix login", result.get("summary"));
        assertEquals("Login broken", result.get("description"));
        assertEquals(List.of("ai-generate"), result.get("labels"));
        assertEquals("High", result.get("priority"));
        assertEquals(true, result.get("dryRun"));
    }

    @Test
    void should_exclude_build_directories() throws Exception {
        Path repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir);

        // Create file in build directory (should be excluded)
        Path buildDir = repoDir.resolve("build/classes");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("App.class"), "binary");

        // Create a normal source file (should be included)
        Path srcDir = repoDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("App.java"), "public class App {}");

        when(sourceControlClient.getDefaultBranch(any())).thenReturn(BranchName.of("main"));
        when(sourceControlClient.downloadArchive(any(), eq(BranchName.of("main")), any(Path.class)))
                .thenReturn(repoDir);
        when(contextStorageService.storeContext(anyString(), anyString())).thenReturn("key");

        Map<String, Object> input = buildValidInput();

        handler.handleRequest(input, lambdaContext);

        // Verify context was stored and source files section has App.java but not
        // .class files
        verify(contextStorageService).storeContext(eq("PROJ-1"),
                argThat(context -> context.contains("App.java") && !context.contains("```\nbinary")));
    }

    @Test
    void should_pass_through_platform_and_repo_on_error() throws Exception {
        when(sourceControlClient.getDefaultBranch(any())).thenThrow(new RuntimeException("Connection refused"));

        Map<String, Object> input = buildValidInput();
        input.put("platform", "GITHUB");
        input.put("repoOwner", "my-org");
        input.put("repoSlug", "my-repo");

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("GITHUB", result.get("platform"));
        assertEquals("my-org", result.get("repoOwner"));
        assertEquals("my-repo", result.get("repoSlug"));
        assertNotNull(result.get("fetchError"));
    }

    // ======== Helpers ========

    private Path createMockRepo() throws IOException {
        Path repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir);

        Path srcDir = repoDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("App.java"), "public class App { public static void main(String[] args) {} }");
        Files.writeString(srcDir.resolve("Service.java"), "public class Service {}");

        Path configDir = repoDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("application.yml"), "server:\n  port: 8080");

        Files.writeString(repoDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(repoDir.resolve(".gitignore"), "build/\n*.class");

        return repoDir;
    }

    private Map<String, Object> buildValidInput() {
        Map<String, Object> input = new HashMap<>();
        input.put("tenantId", "tenant-1");
        input.put("projectKey", "PROJ");
        input.put("ticketId", "12345");
        input.put("ticketKey", "PROJ-1");
        input.put("summary", "Test summary");
        input.put("description", "Test description");
        return input;
    }
}
