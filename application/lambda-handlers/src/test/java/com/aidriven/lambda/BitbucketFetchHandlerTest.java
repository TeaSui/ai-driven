package com.aidriven.lambda;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.CodeContextS3Service;
import com.aidriven.core.service.SecretsService;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
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
import static org.mockito.Mockito.*;

class BitbucketFetchHandlerTest {

    @Mock
    private TicketStateRepository ticketStateRepository;

    @Mock
    private SecretsService secretsService;

    @Mock
    private CodeContextS3Service codeContextS3Service;

    @Mock
    private BitbucketClient bitbucketClient;

    @Mock
    private Context lambdaContext;

    @TempDir
    Path tempDir;

    private BitbucketFetchHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BITBUCKET_SECRET = "arn:aws:secretsmanager:test:bitbucket";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new BitbucketFetchHandler(
                objectMapper, ticketStateRepository, secretsService,
                BITBUCKET_SECRET, codeContextS3Service);
    }

    @Test
    void should_throw_for_null_input() {
        assertThrows(NullPointerException.class,
                () -> handler.handleRequest(null, lambdaContext));
    }

    @Test
    void should_throw_for_empty_input() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.handleRequest(Map.of(), lambdaContext));
    }

    @Test
    void should_throw_for_missing_ticket_id() {
        Map<String, Object> input = Map.of("ticketKey", "PROJ-1");

        assertThrows(IllegalArgumentException.class,
                () -> handler.handleRequest(input, lambdaContext));
    }

    @Test
    void should_throw_for_missing_ticket_key() {
        Map<String, Object> input = Map.of("ticketId", "12345");

        assertThrows(IllegalArgumentException.class,
                () -> handler.handleRequest(input, lambdaContext));
    }

    @Test
    void should_fetch_code_and_store_in_s3() throws Exception {
        Path repoDir = createMockRepo();

        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);
            bbMock.when(() -> BitbucketClient.deleteDirectory(any())).then(invocation -> null);

            when(bitbucketClient.getDefaultBranch()).thenReturn("main");
            when(bitbucketClient.downloadArchive(eq("main"), any(Path.class))).thenReturn(repoDir);
            when(codeContextS3Service.storeContext(anyString(), anyString())).thenReturn("context/PROJ-1/123.txt");

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals("12345", result.get("ticketId"));
            assertEquals("PROJ-1", result.get("ticketKey"));
            assertEquals("context/PROJ-1/123.txt", result.get("codeContextS3Key"));
            assertTrue((int) result.get("fileCount") > 0);
            verify(codeContextS3Service).storeContext(eq("PROJ-1"), anyString());
            verify(ticketStateRepository).save(any());
        }
    }

    @Test
    void should_handle_bitbucket_error_gracefully() {
        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString()))
                    .thenThrow(new RuntimeException("Connection refused"));

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals("12345", result.get("ticketId"));
            assertEquals("PROJ-1", result.get("ticketKey"));
            assertEquals("", result.get("codeContextS3Key"));
            assertNotNull(result.get("fetchError"));
        }
    }

    @Test
    void should_pass_through_input_fields() throws Exception {
        Path repoDir = createMockRepo();

        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);
            bbMock.when(() -> BitbucketClient.deleteDirectory(any())).then(invocation -> null);

            when(bitbucketClient.getDefaultBranch()).thenReturn("main");
            when(bitbucketClient.downloadArchive(anyString(), any(Path.class))).thenReturn(repoDir);
            when(codeContextS3Service.storeContext(anyString(), anyString())).thenReturn("key");

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

        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);
            bbMock.when(() -> BitbucketClient.deleteDirectory(any())).then(invocation -> null);

            when(bitbucketClient.getDefaultBranch()).thenReturn("main");
            when(bitbucketClient.downloadArchive(anyString(), any(Path.class))).thenReturn(repoDir);
            when(codeContextS3Service.storeContext(anyString(), anyString())).thenReturn("key");

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            // Verify context was stored and source files section has App.java but not .class files
            verify(codeContextS3Service).storeContext(eq("PROJ-1"), argThat(context ->
                    context.contains("App.java") && !context.contains("```\nbinary")));
        }
    }

    @Test
    void should_cleanup_tmp_on_success() throws Exception {
        Path repoDir = createMockRepo();

        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);
            bbMock.when(() -> BitbucketClient.deleteDirectory(any())).then(invocation -> null);

            when(bitbucketClient.getDefaultBranch()).thenReturn("main");
            when(bitbucketClient.downloadArchive(anyString(), any(Path.class))).thenReturn(repoDir);
            when(codeContextS3Service.storeContext(anyString(), anyString())).thenReturn("key");

            handler.handleRequest(buildValidInput(), lambdaContext);

            bbMock.verify(() -> BitbucketClient.deleteDirectory(repoDir.getParent()));
        }
    }

    @Test
    void should_cleanup_tmp_on_failure() throws Exception {
        Path repoDir = createMockRepo();

        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);
            bbMock.when(() -> BitbucketClient.deleteDirectory(any())).then(invocation -> null);

            when(bitbucketClient.getDefaultBranch()).thenReturn("main");
            when(bitbucketClient.downloadArchive(anyString(), any(Path.class))).thenReturn(repoDir);
            when(codeContextS3Service.storeContext(anyString(), anyString()))
                    .thenThrow(new RuntimeException("S3 error"));

            handler.handleRequest(buildValidInput(), lambdaContext);

            bbMock.verify(() -> BitbucketClient.deleteDirectory(repoDir.getParent()));
        }
    }

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
        input.put("ticketId", "12345");
        input.put("ticketKey", "PROJ-1");
        input.put("summary", "Test summary");
        input.put("description", "Test description");
        return input;
    }
}
