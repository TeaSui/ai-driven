package com.aidriven.bitbucket;

import com.aidriven.core.model.AgentResult;
import com.aidriven.core.source.RepositoryWriter;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.provider.SourceControlProvider;
import com.aidriven.core.exception.ConfigurationException;
import com.aidriven.core.service.SecretsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for BitbucketClient input validation.
 * Validates null checks, empty strings, and invalid inputs.
 */
class BitbucketClientValidationTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private BitbucketClient bitbucketClient;
    private OperationContext operationContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bitbucketClient = new BitbucketClient(
                "workspace",
                "repo",
                "Basic auth",
                mockHttpClient,
                new com.fasterxml.jackson.databind.ObjectMapper());
        operationContext = OperationContext.builder().tenantId("test-tenant").userId("test-user").build();
    }

    @Test
    void should_throw_null_pointer_exception_for_null_workspace() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient(null, "repo", "auth", mockHttpClient, null);
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_repo_slug() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient("workspace", null, "auth", mockHttpClient, null);
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_username() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient("workspace", "repo", null, mockHttpClient, null);
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_password() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient("workspace", "repo", "Basic auth", null, null);
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "  ", "\t", "\n" })
    void should_throw_exception_for_invalid_branch_name_in_create_branch(String invalidBranch) {
        // When/Then: Verify IllegalArgumentException is thrown
        assertThrows(IllegalArgumentException.class, () -> {
            bitbucketClient.createBranch(operationContext, BranchName.of(invalidBranch), BranchName.of("main"));
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "  " })
    void should_throw_exception_for_invalid_from_branch_in_create_branch(String invalidBranch) {
        // When/Then: Verify IllegalArgumentException is thrown
        assertThrows(IllegalArgumentException.class, () -> {
            bitbucketClient.createBranch(operationContext, BranchName.of("feature-branch"),
                    BranchName.of(invalidBranch));
        });
    }

    @Test
    void should_accept_valid_branch_names() throws Exception {
        // Given: Mock successful responses
        // createBranch calls getBranchCommitHash first, then POST to create branch
        // getBranchCommitHash: checkResponse reads statusCode()+body(), then readTree
        // reads body() again
        // createBranch POST: reads statusCode() twice (line 104), no body() if 201
        String branchResponse = """
                {
                    "target": {
                        "hash": "abc123def456"
                    }
                }
                """;
        // First createBranch: getBranchCommitHash(200) + createBranch two checks (1st
        // call)
        // Second createBranch: getBranchCommitHash(200) + createBranch two checks (2nd
        // call)
        when(mockResponse.statusCode()).thenReturn(
                200, 201, 201, // getBranchCommitHash check + createBranch two checks (1st call)
                200, 201, 201 // getBranchCommitHash check + createBranch two checks (2nd call)
        );
        // body() calls: checkResponse in getBranchCommitHash + readTree in
        // getBranchCommitHash (per call)
        when(mockResponse.body()).thenReturn(
                branchResponse, branchResponse, // 1st createBranch
                branchResponse, branchResponse // 2nd createBranch
        );
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: Create branch with valid names
        assertDoesNotThrow(() -> {
            bitbucketClient.createBranch(operationContext, BranchName.of("feature/new-feature"), BranchName.of("main"));
        });

        assertDoesNotThrow(() -> {
            bitbucketClient.createBranch(operationContext, BranchName.of("bugfix-123"), BranchName.of("develop"));
        });
    }

    @Test
    void should_throw_exception_for_null_files_in_commit_files() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.commitFiles(operationContext, BranchName.of("branch"), null, "message");
        });
    }

    @Test
    void should_handle_empty_files_list_in_commit_files() throws Exception {
        // Given: Empty files list
        List<AgentResult.GeneratedFile> emptyFiles = List.of();
        when(mockResponse.statusCode()).thenReturn(201);
        when(mockResponse.body()).thenReturn("{}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: Commit empty files list
        String result = bitbucketClient.commitFiles(operationContext, BranchName.of("branch"), emptyFiles,
                "Empty commit");

        // Then: Should succeed
        assertEquals("success", result);
    }

    @Test
    void should_throw_exception_for_null_title_in_create_pull_request() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.createPullRequest(operationContext, null, "description", BranchName.of("source"),
                    BranchName.of("dest"));
        });
    }

    @Test
    void should_throw_exception_for_null_source_branch_in_create_pull_request() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.createPullRequest(operationContext, "title", "description", null, BranchName.of("dest"));
        });
    }

    @Test
    void should_throw_exception_for_null_destination_branch_in_create_pull_request() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.createPullRequest(operationContext, "title", "description", BranchName.of("source"), null);
        });
    }

    @Test
    void should_allow_null_description_in_create_pull_request() throws Exception {
        // Given: Mock successful response
        // createPullRequest: reads statusCode() twice (line 205 if-check), then
        // readTree reads body()
        String prResponse = """
                {
                    "id": "1",
                    "links": {
                        "html": {
                            "href": "https://bitbucket.org/workspace/repo/pull-requests/1"
                        }
                    }
                }
                """;
        when(mockResponse.statusCode()).thenReturn(201, 201);
        when(mockResponse.body()).thenReturn(prResponse);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: Create PR with null description
        SourceControlProvider.PullRequestResult result = bitbucketClient.createPullRequest(
                operationContext, "title", null, BranchName.of("source"), BranchName.of("dest"));

        // Then: Should succeed
        assertNotNull(result);
        assertEquals("1", result.id());
    }

    @Test
    void should_initialize_from_secrets() {
        SecretsService secretsService = mock(SecretsService.class);
        String secretArn = "test-arn";
        when(secretsService.getSecretAs(eq(secretArn), eq(BitbucketClient.BitbucketSecret.class)))
                .thenReturn(new BitbucketClient.BitbucketSecret("test-ws", "test-repo", "test-user", "test-pass"));

        BitbucketClient client = BitbucketClient.fromSecrets(secretsService, secretArn);

        assertNotNull(client);
    }

    @Test
    void should_throw_when_secrets_missing_required_key() {
        SecretsService secretsService = mock(SecretsService.class);
        String secretArn = "test-arn";
        when(secretsService.getSecretAs(eq(secretArn), eq(BitbucketClient.BitbucketSecret.class)))
                .thenReturn(new BitbucketClient.BitbucketSecret("test-ws", "test-repo", null, null));

        ConfigurationException ex = assertThrows(ConfigurationException.class,
                () -> BitbucketClient.fromSecrets(secretsService, secretArn));

        assertTrue(ex.getMessage().contains("missing required fields"));
    }

    @Test
    void should_url_encode_workspace_and_repo_in_commit_files() throws Exception {
        // Given: A client with workspace/repo that contain special characters
        BitbucketClient specialClient = new BitbucketClient(
                "my workspace",
                "my repo",
                "Basic auth",
                mockHttpClient,
                new com.fasterxml.jackson.databind.ObjectMapper());

        List<AgentResult.GeneratedFile> files = List.of();
        when(mockResponse.statusCode()).thenReturn(201);
        when(mockResponse.body()).thenReturn("{}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: commitFiles is called
        specialClient.commitFiles(operationContext, BranchName.of("branch"), files, "test commit");

        // Then: The URL should contain encoded workspace and repo
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
        String uri = captor.getValue().uri().toString();
        assertTrue(uri.contains("my+workspace"), "workspace should be URL-encoded, got: " + uri);
        assertTrue(uri.contains("my+repo"), "repoSlug should be URL-encoded, got: " + uri);
    }

    @Test
    void should_url_encode_workspace_and_repo_in_create_pull_request() throws Exception {
        // Given: A client with workspace/repo that contain special characters
        BitbucketClient specialClient = new BitbucketClient(
                "my workspace",
                "my repo",
                "Basic auth",
                mockHttpClient,
                new com.fasterxml.jackson.databind.ObjectMapper());

        String prResponse = """
                {
                    "id": "1",
                    "links": {
                        "html": {
                            "href": "https://bitbucket.org/ws/repo/pull-requests/1"
                        }
                    }
                }
                """;
        when(mockResponse.statusCode()).thenReturn(201, 201);
        when(mockResponse.body()).thenReturn(prResponse);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: createPullRequest is called
        specialClient.createPullRequest(operationContext, "title", "desc", BranchName.of("source"),
                BranchName.of("dest"));

        // Then: The URL should contain encoded workspace and repo
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
        String uri = captor.getValue().uri().toString();
        assertTrue(uri.contains("my+workspace"), "workspace should be URL-encoded, got: " + uri);
        assertTrue(uri.contains("my+repo"), "repoSlug should be URL-encoded, got: " + uri);
    }

    @Test
    void should_skip_nodes_with_missing_type_in_get_file_tree() throws Exception {
        // Given: Response with a node missing the "type" field (no directories to avoid
        // recursion)
        String responseJson = """
                {
                    "values": [
                        {"path": "src/Main.java"},
                        {"type": "commit_file", "path": "src/App.java"}
                    ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: getFileTree is called
        List<String> files = bitbucketClient.getFileTree(operationContext, BranchName.of("main"), null);

        // Then: Only the valid commit_file node should be included; the node without
        // type is skipped
        assertEquals(1, files.size());
        assertEquals("src/App.java", files.get(0));
    }

    @Test
    void should_skip_nodes_with_missing_path_in_get_file_tree() throws Exception {
        // Given: Response with a node missing the "path" field
        String responseJson = """
                {
                    "values": [
                        {"type": "commit_file"},
                        {"type": "commit_file", "path": "src/Valid.java"}
                    ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: getFileTree is called
        List<String> files = bitbucketClient.getFileTree(operationContext, BranchName.of("main"), null);

        // Then: Only the valid node should be included
        assertEquals(1, files.size());
        assertEquals("src/Valid.java", files.get(0));
    }
}
