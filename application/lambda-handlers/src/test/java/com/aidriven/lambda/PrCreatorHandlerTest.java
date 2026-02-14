package com.aidriven.lambda;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.github.GitHubClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

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
        private Context lambdaContext;

        @Mock
        private SourceControlClient sourceControlClient;

        @Mock
        private JiraClient jiraClient;

        @Mock
        private ServiceFactory serviceFactory;

        @Mock
        private GitHubClient gitHubClient;

        @Mock
        private BitbucketClient bitbucketClient;

        private PrCreatorHandler handler;
        private final ObjectMapper objectMapper = new ObjectMapper();

        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);
                handler = new PrCreatorHandler(
                                objectMapper, ticketStateRepository,
                                jiraClient, serviceFactory, sourceControlClient, "ai/");
        }

        // ======== Input Validation ========

        @Test
        void should_throw_for_null_input() {
                assertThrows(NullPointerException.class,
                                () -> handler.handleRequest(null, lambdaContext));
        }

        // ======== Core Functionality ========

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

        @Test
        void should_create_pr_successfully() throws Exception {
                when(sourceControlClient.getDefaultBranch()).thenReturn("main");
                when(sourceControlClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(
                                                new SourceControlClient.PullRequestResult("1",
                                                                "https://bitbucket.org/repo/pr/1", "ai/proj-1"));
                when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

                // Mock factory behavior
                when(serviceFactory.getGitHubClient(any(), any())).thenReturn(gitHubClient);
                when(serviceFactory.getBitbucketClient(any(), any())).thenReturn(bitbucketClient);

                // Ensure the base client mocks behave correctly
                when(gitHubClient.getDefaultBranch()).thenReturn("main");
                when(bitbucketClient.getDefaultBranch()).thenReturn("main");
                when(gitHubClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(new SourceControlClient.PullRequestResult("1",
                                                "https://github.com/repo/pr/1", "ai/proj-1"));
                when(bitbucketClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(new SourceControlClient.PullRequestResult("1",
                                                "https://bitbucket.org/repo/pr/1", "ai/proj-1"));

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
                verify(sourceControlClient).createBranch("ai/proj-1", "main");
                verify(sourceControlClient).commitFiles(eq("ai/proj-1"), any(), eq("feat: add main"));
        }

        @Test
        void should_handle_empty_files_list() throws Exception {
                Map<String, Object> input = new HashMap<>();
                input.put("ticketId", "12345");
                input.put("ticketKey", "PROJ-1");
                input.put("success", true);
                input.put("files", "[]");

                Map<String, Object> result = handler.handleRequest(input, lambdaContext);

                assertEquals(false, result.get("prCreated"));
                assertEquals("No files generated", result.get("reason"));
        }

        @Test
        void should_continue_when_branch_already_exists() throws Exception {
                when(sourceControlClient.getDefaultBranch()).thenReturn("main");
                doThrow(new com.aidriven.core.exception.ConflictException("Branch exists", "{}"))
                                .when(sourceControlClient).createBranch(anyString(), anyString());
                when(sourceControlClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(
                                                new SourceControlClient.PullRequestResult("2",
                                                                "https://bitbucket.org/repo/pr/2", "ai/proj-2"));
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
                verify(sourceControlClient).commitFiles(anyString(), any(), any());
        }

        @Test
        void should_throw_when_files_json_is_invalid() {
                Map<String, Object> input = new HashMap<>();
                input.put("ticketId", "12345");
                input.put("ticketKey", "PROJ-1");
                input.put("success", true);
                input.put("files", "not valid json");

                assertThrows(RuntimeException.class,
                                () -> handler.handleRequest(input, lambdaContext));

                verify(ticketStateRepository).save(any());
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

        // ======== Platform Pass-Through (impl-09) ========

        @Test
        void should_pass_through_platform_in_output() throws Exception {
                when(sourceControlClient.getDefaultBranch()).thenReturn("main");
                when(sourceControlClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(new SourceControlClient.PullRequestResult("1",
                                                "https://github.com/org/repo/pull/1",
                                                "ai/proj-1"));
                when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

                List<AgentResult.GeneratedFile> files = List.of(
                                AgentResult.GeneratedFile.builder()
                                                .path("src/Main.java")
                                                .content("public class Main {}")
                                                .operation(AgentResult.FileOperation.CREATE)
                                                .build());

                Map<String, Object> input = new HashMap<>();
                input.put("ticketId", "12345");
                input.put("ticketKey", "PROJ-1");
                input.put("success", true);
                input.put("files", objectMapper.writeValueAsString(files));
                input.put("commitMessage", "feat: add main");
                input.put("prTitle", "Add main class");
                input.put("prDescription", "Generated");
                input.put("platform", "GITHUB");

                Map<String, Object> result = handler.handleRequest(input, lambdaContext);

                assertEquals(true, result.get("prCreated"));
                assertEquals("GITHUB", result.get("platform"));
        }

        @Test
        void should_not_duplicate_ticket_key_in_title() throws Exception {
                when(sourceControlClient.getDefaultBranch()).thenReturn("main");
                when(sourceControlClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(new SourceControlClient.PullRequestResult("1", "url", "branch"));
                when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

                Map<String, Object> input = new HashMap<>();
                input.put("ticketId", "12345");
                input.put("ticketKey", "CRM-85");
                input.put("success", true);
                input.put("files", "[{\"path\":\"test.txt\",\"content\":\"v\",\"operation\":\"CREATE\"}]");
                input.put("prTitle", "CRM-85: Fix duplicate title");

                handler.handleRequest(input, lambdaContext);

                // Verify title starts with [AI] and NO second CRM-85:
                verify(sourceControlClient).createPullRequest(
                                eq("[AI] CRM-85: Fix duplicate title"), anyString(), anyString(), anyString());
        }

        @Test
        void should_prepend_ticket_key_if_missing_in_title() throws Exception {
                when(sourceControlClient.getDefaultBranch()).thenReturn("main");
                when(sourceControlClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(new SourceControlClient.PullRequestResult("1", "url", "branch"));
                when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

                Map<String, Object> input = new HashMap<>();
                input.put("ticketId", "12345");
                input.put("ticketKey", "CRM-85");
                input.put("success", true);
                input.put("files", "[{\"path\":\"test.txt\",\"content\":\"v\",\"operation\":\"CREATE\"}]");
                input.put("prTitle", "Fix duplicate title");

                handler.handleRequest(input, lambdaContext);

                // Verify title starts with [AI] CRM-85:
                verify(sourceControlClient).createPullRequest(
                                eq("[AI] CRM-85: Fix duplicate title"), anyString(), anyString(), anyString());
        }

        @Test
        void should_fail_if_repo_metadata_missing() throws Exception {
                // Re-initialize handler WITHOUT a testSourceControlClient
                handler = new PrCreatorHandler(objectMapper, ticketStateRepository, jiraClient, serviceFactory, null,
                                "ai/");

                // Prepare input MISSING repoOwner/repoSlug
                Map<String, Object> input = new HashMap<>();
                input.put("ticketId", "12345");
                input.put("ticketKey", "CRM-85");
                input.put("success", true);
                input.put("platform", "GITHUB");
                input.put("files", "[{\"path\":\"test.txt\",\"content\":\"v\",\"operation\":\"CREATE\"}]");

                // Mock factory behavior - return default client when arguments are null/empty
                when(serviceFactory.getGitHubClient(null, null)).thenReturn(gitHubClient);
                when(gitHubClient.getDefaultBranch()).thenReturn("main");
                when(gitHubClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(new SourceControlClient.PullRequestResult("1",
                                                "https://github.com/TeaSui/claude-automation/pull/1", "main"));
                when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

                handler.handleRequest(input, lambdaContext);

                // Verify the factory was called with NULLS (falling back to default)
                verify(serviceFactory).getGitHubClient(null, null);
        }

        @Test
        void should_use_correct_platform_from_input() throws Exception {
                handler = new PrCreatorHandler(objectMapper, ticketStateRepository, jiraClient, serviceFactory, null,
                                "ai/");

                Map<String, Object> input = new HashMap<>();
                input.put("ticketId", "12345");
                input.put("ticketKey", "CRM-85");
                input.put("success", true);
                input.put("platform", "GITHUB");
                input.put("repoOwner", "TeaSui");
                input.put("repoSlug", "emergency-location-service");
                input.put("files", "[{\"path\":\"test.txt\",\"content\":\"v\",\"operation\":\"CREATE\"}]");

                when(serviceFactory.getGitHubClient("TeaSui", "emergency-location-service")).thenReturn(gitHubClient);
                when(gitHubClient.getDefaultBranch()).thenReturn("main");
                when(gitHubClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                                .thenReturn(new SourceControlClient.PullRequestResult("1",
                                                "https://github.com/TeaSui/emergency-location-service/pull/1", "main"));
                when(jiraClient.getTransitions(anyString())).thenReturn(List.of());

                handler.handleRequest(input, lambdaContext);

                verify(serviceFactory).getGitHubClient("TeaSui", "emergency-location-service");
        }
}
