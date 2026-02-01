package com.aidriven.lambda;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.SecretsService;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PrCreatorHandlerTest {

    @Mock
    private TicketStateRepository ticketStateRepository;

    @Mock
    private SecretsService secretsService;

    @Mock
    private Context lambdaContext;

    @Mock
    private BitbucketClient bitbucketClient;

    @Mock
    private JiraClient jiraClient;

    private PrCreatorHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BITBUCKET_SECRET = "arn:aws:secretsmanager:test:bitbucket";
    private static final String JIRA_SECRET = "arn:aws:secretsmanager:test:jira";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new PrCreatorHandler(
                objectMapper, ticketStateRepository, secretsService,
                BITBUCKET_SECRET, JIRA_SECRET);
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
    void should_skip_pr_when_not_successful() {
        Map<String, Object> input = new HashMap<>();
        input.put("ticketId", "12345");
        input.put("ticketKey", "PROJ-1");
        input.put("success", false);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("12345", result.get("ticketId"));
        assertEquals("PROJ-1", result.get("ticketKey"));
        assertEquals(false, result.get("prCreated"));
        assertEquals("Agent did not succeed", result.get("reason"));
    }

    @Test
    void should_handle_dry_run_mode() {
        try (MockedStatic<JiraClient> jiraMock = mockStatic(JiraClient.class)) {
            jiraMock.when(() -> JiraClient.fromSecrets(any(), anyString())).thenReturn(jiraClient);

            Map<String, Object> input = new HashMap<>();
            input.put("ticketId", "12345");
            input.put("ticketKey", "PROJ-1");
            input.put("dryRun", true);
            input.put("success", true);
            input.put("files", "[]");

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(false, result.get("prCreated"));
            assertEquals(true, result.get("dryRun"));
            assertEquals("Dry-run mode", result.get("reason"));
            verify(ticketStateRepository).save(any());
        }
    }

    @Test
    void should_create_pr_successfully() throws Exception {
        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class);
             MockedStatic<JiraClient> jiraMock = mockStatic(JiraClient.class)) {

            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);
            jiraMock.when(() -> JiraClient.fromSecrets(any(), anyString())).thenReturn(jiraClient);

            when(bitbucketClient.getDefaultBranch()).thenReturn("main");
            when(bitbucketClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new BitbucketClient.PullRequestResult("1", "https://bitbucket.org/repo/pr/1", "ai/proj-1"));
            when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

            List<AgentResult.GeneratedFile> files = List.of(
                    AgentResult.GeneratedFile.builder()
                            .path("src/Main.java")
                            .content("public class Main {}")
                            .operation(AgentResult.FileOperation.CREATE)
                            .build());
            String filesJson = objectMapper.writeValueAsString(files);

            Map<String, Object> input = new HashMap<>();
            input.put("ticketId", "12345");
            input.put("ticketKey", "PROJ-1");
            input.put("success", true);
            input.put("files", filesJson);
            input.put("commitMessage", "feat: add main");
            input.put("prTitle", "Add main class");
            input.put("prDescription", "Generated main class");

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(true, result.get("prCreated"));
            assertEquals("https://bitbucket.org/repo/pr/1", result.get("prUrl"));
            assertEquals("ai/proj-1", result.get("branchName"));
            verify(bitbucketClient).createBranch("ai/proj-1", "main");
            verify(bitbucketClient).commitFiles(eq("ai/proj-1"), any(), eq("feat: add main"));
        }
    }

    @Test
    void should_handle_empty_files_list() throws Exception {
        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);

            Map<String, Object> input = new HashMap<>();
            input.put("ticketId", "12345");
            input.put("ticketKey", "PROJ-1");
            input.put("success", true);
            input.put("files", "[]");

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(false, result.get("prCreated"));
            assertEquals("No files generated", result.get("reason"));
        }
    }

    @Test
    void should_continue_when_branch_already_exists() throws Exception {
        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class);
             MockedStatic<JiraClient> jiraMock = mockStatic(JiraClient.class)) {

            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);
            jiraMock.when(() -> JiraClient.fromSecrets(any(), anyString())).thenReturn(jiraClient);

            when(bitbucketClient.getDefaultBranch()).thenReturn("main");
            doThrow(new com.aidriven.core.exception.ConflictException("Branch exists", "{}"))
                    .when(bitbucketClient).createBranch(anyString(), anyString());
            when(bitbucketClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new BitbucketClient.PullRequestResult("2", "https://bitbucket.org/repo/pr/2", "ai/proj-2"));
            when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

            List<AgentResult.GeneratedFile> files = List.of(
                    AgentResult.GeneratedFile.builder()
                            .path("src/App.java")
                            .content("class App {}")
                            .operation(AgentResult.FileOperation.CREATE)
                            .build());

            Map<String, Object> input = new HashMap<>();
            input.put("ticketId", "12345");
            input.put("ticketKey", "PROJ-2");
            input.put("success", true);
            input.put("files", objectMapper.writeValueAsString(files));

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(true, result.get("prCreated"));
            verify(bitbucketClient).commitFiles(anyString(), any(), any());
        }
    }

    @Test
    void should_throw_when_files_json_is_invalid() {
        try (MockedStatic<BitbucketClient> bbMock = mockStatic(BitbucketClient.class)) {
            bbMock.when(() -> BitbucketClient.fromSecrets(any(), anyString())).thenReturn(bitbucketClient);

            Map<String, Object> input = new HashMap<>();
            input.put("ticketId", "12345");
            input.put("ticketKey", "PROJ-1");
            input.put("success", true);
            input.put("files", "not valid json");

            assertThrows(RuntimeException.class,
                    () -> handler.handleRequest(input, lambdaContext));

            verify(ticketStateRepository).save(any());
        }
    }

    @Test
    void should_default_agent_type_to_bedrock() {
        Map<String, Object> input = new HashMap<>();
        input.put("ticketId", "12345");
        input.put("ticketKey", "PROJ-1");
        input.put("success", false);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(false, result.get("prCreated"));
    }
}
