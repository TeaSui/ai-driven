package com.aidriven.github;

import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.provider.SourceControlProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.aidriven.core.service.SecretsService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GitHubClientTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private GitHubClient client;
    private OperationContext operationContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        client = new GitHubClient("test-owner", "test-repo", "Bearer test-token", mockHttpClient, new ObjectMapper());
        operationContext = OperationContext.builder().tenantId("test-tenant").userId("test-user").build();
    }

    @Nested
    class GetDefaultBranch {
        @Test
        void should_return_default_branch() throws Exception {
            when(mockResponse.statusCode()).thenReturn(200, 200);
            when(mockResponse.body()).thenReturn("{\"default_branch\": \"main\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            BranchName result = client.getDefaultBranch(operationContext);
            assertEquals("main", result.name());
        }
    }

    @Nested
    class CreatePullRequest {
        @Test
        void should_create_pull_request_successfully() throws Exception {
            String prResponse = """
                    {
                        "number": 42,
                        "html_url": "https://github.com/test-owner/test-repo/pull/42"
                    }
                    """;
            when(mockResponse.statusCode()).thenReturn(201, 201);
            when(mockResponse.body()).thenReturn(prResponse);
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            SourceControlProvider.PullRequestResult result = client.createPullRequest(
                    operationContext, "title", "description", BranchName.of("source"), BranchName.of("dest"));

            assertNotNull(result);
            assertEquals("42", result.id());
            assertEquals("https://github.com/test-owner/test-repo/pull/42", result.url());
            assertEquals("source", result.branch().name());
        }

        @Test
        void should_handle_null_description() throws Exception {
            String prResponse = """
                    {
                        "number": 1,
                        "html_url": "https://github.com/test-owner/test-repo/pull/1"
                    }
                    """;
            when(mockResponse.statusCode()).thenReturn(201, 201);
            when(mockResponse.body()).thenReturn(prResponse);
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            SourceControlProvider.PullRequestResult result = client.createPullRequest(
                    operationContext, "title", null, BranchName.of("source"), BranchName.of("dest"));

            assertNotNull(result);
            assertEquals("1", result.id());
        }
    }

    @Nested
    class GetFileTree {
        @Test
        void should_return_file_list() throws Exception {
            String treeResponse = """
                    {
                        "sha": "abc",
                        "tree": [
                            {"path": "src/Main.java", "type": "blob"},
                            {"path": "src", "type": "tree"},
                            {"path": "README.md", "type": "blob"}
                        ]
                    }
                    """;
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(treeResponse);
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            List<String> result = client.getFileTree(operationContext, BranchName.of("main"), null);

            assertEquals(2, result.size());
            assertTrue(result.contains("src/Main.java"));
            assertTrue(result.contains("README.md"));
        }

        @Test
        void should_filter_by_path_prefix() throws Exception {
            String treeResponse = """
                    {
                        "sha": "abc",
                        "tree": [
                            {"path": "src/Main.java", "type": "blob"},
                            {"path": "test/MainTest.java", "type": "blob"}
                        ]
                    }
                    """;
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(treeResponse);
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            List<String> result = client.getFileTree(operationContext, BranchName.of("main"), "src");

            assertEquals(1, result.size());
            assertEquals("src/Main.java", result.get(0));
        }

        @Test
        void should_return_empty_list_on_error() throws Exception {
            when(mockResponse.statusCode()).thenReturn(404);
            when(mockResponse.body()).thenReturn("{\"message\": \"Not Found\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            List<String> result = client.getFileTree(operationContext, BranchName.of("main"), null);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class SearchFiles {
        @Test
        void should_return_search_results() throws Exception {
            String searchResponse = """
                    {
                        "total_count": 2,
                        "items": [
                            {"path": "src/UserService.java"},
                            {"path": "src/UserController.java"}
                        ]
                    }
                    """;
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(searchResponse);
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            List<String> result = client.searchFiles(operationContext, "UserService");

            assertEquals(2, result.size());
            assertTrue(result.contains("src/UserService.java"));
        }

        @Test
        void should_return_empty_on_search_failure() throws Exception {
            when(mockResponse.statusCode()).thenReturn(403);
            when(mockResponse.body()).thenReturn("{\"message\": \"rate limit\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            List<String> result = client.searchFiles(operationContext, "query");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class GetFileContent {
        @Test
        void should_decode_base64_content() throws Exception {
            String contentResponse = """
                    {
                        "encoding": "base64",
                        "content": "cHVibGljIGNsYXNzIE1haW4ge30="
                    }
                    """;
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(contentResponse);
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            String result = client.getFileContent(operationContext, BranchName.of("main"), "src/Main.java");

            assertEquals("public class Main {}", result);
        }

        @Test
        void should_return_null_on_not_found() throws Exception {
            when(mockResponse.statusCode()).thenReturn(404);
            when(mockResponse.body()).thenReturn("{\"message\": \"Not Found\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            String result = client.getFileContent(operationContext, BranchName.of("main"), "nonexistent.java");

            assertNull(result);
        }
    }

    @Nested
    class FromSecrets {
        @Test
        void should_create_client_from_secrets() {
            SecretsService secretsService = mock(SecretsService.class);
            when(secretsService.getSecretAs("arn", GitHubClient.GitHubSecret.class)).thenReturn(
                    new GitHubClient.GitHubSecret("my-org", "my-repo", "ghp_test123"));

            GitHubClient result = GitHubClient.fromSecrets(secretsService, "arn");
            assertNotNull(result);
        }

        @Test
        void should_throw_on_missing_token() {
            SecretsService secretsService = mock(SecretsService.class);
            when(secretsService.getSecretAs("arn", GitHubClient.GitHubSecret.class)).thenReturn(
                    new GitHubClient.GitHubSecret("my-org", "my-repo", null));

            assertThrows(com.aidriven.core.exception.ConfigurationException.class,
                    () -> GitHubClient.fromSecrets(secretsService, "arn"));
        }
    }

    @Nested
    class ParseRepoUrl {
        @Test
        void should_parse_https_url() {
            GitHubClient.ParsedRepoUrl result = GitHubClient.parseRepoUrl("https://github.com/owner/repo");
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
        }

        @Test
        void should_parse_https_url_with_git_suffix() {
            GitHubClient.ParsedRepoUrl result = GitHubClient.parseRepoUrl("https://github.com/owner/repo.git");
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
        }

        @Test
        void should_parse_ssh_url() {
            GitHubClient.ParsedRepoUrl result = GitHubClient.parseRepoUrl("git@github.com:owner/repo.git");
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
        }

        @Test
        void should_parse_api_url() {
            GitHubClient.ParsedRepoUrl result = GitHubClient.parseRepoUrl("https://api.github.com/repos/owner/repo");
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
        }

        @Test
        void should_throw_on_null_url() {
            assertThrows(NullPointerException.class, () -> GitHubClient.parseRepoUrl(null));
        }

        @Test
        void should_throw_on_empty_url() {
            assertThrows(IllegalArgumentException.class, () -> GitHubClient.parseRepoUrl(""));
        }

        @Test
        void should_throw_on_unrecognized_url() {
            assertThrows(IllegalArgumentException.class,
                    () -> GitHubClient.parseRepoUrl("https://gitlab.com/owner/repo"));
        }
    }
}
