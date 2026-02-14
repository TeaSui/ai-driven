package com.aidriven.github;

import com.aidriven.core.model.AgentResult;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.source.SourceControlClient;
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
import java.util.Map;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        client = new GitHubClient("Bearer test-token", mockHttpClient, objectMapper, "test-owner", "test-repo");
    }

    @Nested
    class GetDefaultBranch {
        @Test
        void should_return_default_branch() throws Exception {
            when(mockResponse.statusCode()).thenReturn(200, 200);
            when(mockResponse.body()).thenReturn("{\"default_branch\": \"main\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            String result = client.getDefaultBranch();
            assertEquals("main", result);
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

            SourceControlClient.PullRequestResult result = client.createPullRequest(
                    "Test PR", "Description", "feature-branch", "main");

            assertNotNull(result);
            assertEquals("42", result.id());
            assertEquals("https://github.com/test-owner/test-repo/pull/42", result.url());
            assertEquals("feature-branch", result.branchName());
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

            SourceControlClient.PullRequestResult result = client.createPullRequest(
                    "title", null, "source", "dest");

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

            List<String> result = client.getFileTree("main", null);

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

            List<String> result = client.getFileTree("main", "src");

            assertEquals(1, result.size());
            assertEquals("src/Main.java", result.get(0));
        }

        @Test
        void should_return_empty_list_on_error() throws Exception {
            when(mockResponse.statusCode()).thenReturn(404);
            when(mockResponse.body()).thenReturn("{\"message\": \"Not Found\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            List<String> result = client.getFileTree("main", null);

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

            List<String> result = client.searchFiles("UserService");

            assertEquals(2, result.size());
            assertTrue(result.contains("src/UserService.java"));
        }

        @Test
        void should_return_empty_on_search_failure() throws Exception {
            when(mockResponse.statusCode()).thenReturn(403);
            when(mockResponse.body()).thenReturn("{\"message\": \"rate limit\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            List<String> result = client.searchFiles("query");

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

            String result = client.getFileContent("main", "src/Main.java");

            assertEquals("public class Main {}", result);
        }

        @Test
        void should_return_null_on_not_found() throws Exception {
            when(mockResponse.statusCode()).thenReturn(404);
            when(mockResponse.body()).thenReturn("{\"message\": \"Not Found\"}");
            when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResponse);

            String result = client.getFileContent("main", "nonexistent.java");

            assertNull(result);
        }
    }

    @Nested
    class FromSecrets {
        @Test
        void should_create_client_from_secrets() {
            SecretsService secretsService = mock(SecretsService.class);
            when(secretsService.getSecretJson("arn")).thenReturn(Map.of(
                    "owner", "my-org",
                    "repo", "my-repo",
                    "token", "ghp_test123"));

            GitHubClient result = GitHubClient.fromSecrets(secretsService, "arn");
            assertNotNull(result);
        }

        @Test
        void should_throw_on_missing_token() {
            SecretsService secretsService = mock(SecretsService.class);
            when(secretsService.getSecretJson("arn")).thenReturn(Map.of(
                    "owner", "my-org",
                    "repo", "my-repo"));

            assertThrows(RuntimeException.class,
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
