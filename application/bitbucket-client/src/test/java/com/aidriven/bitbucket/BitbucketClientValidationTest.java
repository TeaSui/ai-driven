package com.aidriven.bitbucket;

import com.aidriven.core.model.AgentResult;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bitbucketClient = new BitbucketClient("workspace", "repo", "user", "pass");
        try {
            var field = BitbucketClient.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(bitbucketClient, mockHttpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock HttpClient", e);
        }
    }

    @Test
    void should_throw_null_pointer_exception_for_null_workspace() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient(null, "repo", "user", "pass");
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_repo_slug() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient("workspace", null, "user", "pass");
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_username() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient("workspace", "repo", null, "pass");
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_password() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            new BitbucketClient("workspace", "repo", "user", null);
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "\t", "\n"})
    void should_throw_exception_for_invalid_branch_name_in_create_branch(String invalidBranch) {
        // When/Then: Verify IllegalArgumentException is thrown
        assertThrows(IllegalArgumentException.class, () -> {
            bitbucketClient.createBranch(invalidBranch, "main");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    void should_throw_exception_for_invalid_from_branch_in_create_branch(String invalidBranch) {
        // When/Then: Verify IllegalArgumentException is thrown
        assertThrows(IllegalArgumentException.class, () -> {
            bitbucketClient.createBranch("feature-branch", invalidBranch);
        });
    }

    @Test
    void should_accept_valid_branch_names() throws Exception {
        // Given: Mock successful responses
        // createBranch calls getBranchCommitHash first, then POST to create branch
        // getBranchCommitHash: checkResponse reads statusCode()+body(), then readTree reads body() again
        // createBranch POST: reads statusCode() twice (line 104), no body() if 201
        String branchResponse = """
                {
                    "target": {
                        "hash": "abc123def456"
                    }
                }
                """;
        // First createBranch: getBranchCommitHash(200) + createBranch POST(201)
        // Second createBranch: getBranchCommitHash(200) + createBranch POST(201)
        when(mockResponse.statusCode()).thenReturn(
                200, 201, 201,   // getBranchCommitHash check + createBranch two checks (1st call)
                200, 201, 201    // getBranchCommitHash check + createBranch two checks (2nd call)
        );
        // body() calls: checkResponse in getBranchCommitHash + readTree in getBranchCommitHash (per call)
        when(mockResponse.body()).thenReturn(
                branchResponse, branchResponse,  // 1st createBranch
                branchResponse, branchResponse   // 2nd createBranch
        );
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: Create branch with valid names
        assertDoesNotThrow(() -> {
            bitbucketClient.createBranch("feature/new-feature", "main");
        });

        assertDoesNotThrow(() -> {
            bitbucketClient.createBranch("bugfix-123", "develop");
        });
    }

    @Test
    void should_throw_exception_for_null_files_in_commit_files() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.commitFiles("branch", null, "message");
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
        String result = bitbucketClient.commitFiles("branch", emptyFiles, "Empty commit");

        // Then: Should succeed
        assertEquals("success", result);
    }

    @Test
    void should_throw_exception_for_null_title_in_create_pull_request() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.createPullRequest(null, "description", "source", "dest");
        });
    }

    @Test
    void should_throw_exception_for_null_source_branch_in_create_pull_request() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.createPullRequest("title", "description", null, "dest");
        });
    }

    @Test
    void should_throw_exception_for_null_destination_branch_in_create_pull_request() {
        // When/Then: Verify exception is thrown
        assertThrows(Exception.class, () -> {
            bitbucketClient.createPullRequest("title", "description", "source", null);
        });
    }

    @Test
    void should_allow_null_description_in_create_pull_request() throws Exception {
        // Given: Mock successful response
        // createPullRequest: reads statusCode() twice (line 205 if-check), then readTree reads body()
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
        BitbucketClient.PullRequestResult result = bitbucketClient.createPullRequest(
                "title", null, "source", "dest");

        // Then: Should succeed
        assertNotNull(result);
        assertEquals("1", result.id());
    }
}
