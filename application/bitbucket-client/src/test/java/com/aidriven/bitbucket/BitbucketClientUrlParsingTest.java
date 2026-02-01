package com.aidriven.bitbucket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BitbucketClient URL parsing functionality.
 * Validates parsing of various Bitbucket URL formats.
 */
class BitbucketClientUrlParsingTest {

    @ParameterizedTest
    @CsvSource({
            "https://bitbucket.org/myworkspace/myrepo, myworkspace, myrepo",
            "https://bitbucket.org/workspace123/repo-name, workspace123, repo-name",
            "https://bitbucket.org/my-workspace/my.repo, my-workspace, my.repo"
    })
    void should_parse_https_url_format(String url, String expectedWorkspace, String expectedRepo) {
        // When: Parse HTTPS URL
        BitbucketClient.ParsedRepoUrl parsed = BitbucketClient.parseRepoUrl(url);

        // Then: Verify correct parsing
        assertEquals(expectedWorkspace, parsed.workspace());
        assertEquals(expectedRepo, parsed.repoSlug());
    }

    @ParameterizedTest
    @CsvSource({
            "https://bitbucket.org/myworkspace/myrepo.git, myworkspace, myrepo",
            "https://bitbucket.org/workspace/repo-name.git, workspace, repo-name",
            "http://bitbucket.org/test/project.git, test, project"
    })
    void should_parse_https_url_with_git_suffix(String url, String expectedWorkspace, String expectedRepo) {
        // When: Parse HTTPS URL with .git suffix
        BitbucketClient.ParsedRepoUrl parsed = BitbucketClient.parseRepoUrl(url);

        // Then: Verify .git is removed and parsing is correct
        assertEquals(expectedWorkspace, parsed.workspace());
        assertEquals(expectedRepo, parsed.repoSlug());
    }

    @ParameterizedTest
    @CsvSource({
            "git@bitbucket.org:myworkspace/myrepo.git, myworkspace, myrepo",
            "git@bitbucket.org:workspace/repo-name.git, workspace, repo-name",
            "git@bitbucket.org:test-workspace/test-repo, test-workspace, test-repo"
    })
    void should_parse_ssh_url_format(String url, String expectedWorkspace, String expectedRepo) {
        // When: Parse SSH URL
        BitbucketClient.ParsedRepoUrl parsed = BitbucketClient.parseRepoUrl(url);

        // Then: Verify correct parsing
        assertEquals(expectedWorkspace, parsed.workspace());
        assertEquals(expectedRepo, parsed.repoSlug());
    }

    @Test
    void should_parse_api_url_format() {
        // Given: API URL format
        String url = "https://api.bitbucket.org/2.0/repositories/myworkspace/myrepo";

        // When: Parse API URL
        BitbucketClient.ParsedRepoUrl parsed = BitbucketClient.parseRepoUrl(url);

        // Then: Verify correct parsing
        assertEquals("myworkspace", parsed.workspace());
        assertEquals("myrepo", parsed.repoSlug());
    }

    @Test
    void should_handle_trailing_slash() {
        // Given: URL with trailing slash
        String url = "https://bitbucket.org/workspace/repo/";

        // When: Parse URL
        BitbucketClient.ParsedRepoUrl parsed = BitbucketClient.parseRepoUrl(url);

        // Then: Verify trailing slash is removed
        assertEquals("workspace", parsed.workspace());
        assertEquals("repo", parsed.repoSlug());
    }

    @ParameterizedTest
    @CsvSource({
            "invalid-url",
            "http://github.com/user/repo",
            "https://gitlab.com/user/repo",
            "ftp://bitbucket.org/workspace/repo",
            "bitbucket.org/workspace/repo"
    })
    void should_throw_exception_for_invalid_url_format(String invalidUrl) {
        // When/Then: Verify IllegalArgumentException is thrown
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            BitbucketClient.parseRepoUrl(invalidUrl);
        });

        assertTrue(exception.getMessage().contains("Unrecognized Bitbucket URL format") ||
                   exception.getMessage().contains("URL"));
    }

    @Test
    void should_throw_exception_for_null_url() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            BitbucketClient.parseRepoUrl(null);
        });
    }

    @Test
    void should_throw_exception_for_empty_url() {
        // When/Then: Verify IllegalArgumentException is thrown
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            BitbucketClient.parseRepoUrl("");
        });

        assertTrue(exception.getMessage().contains("URL must not be empty"));
    }

    @Test
    void should_throw_exception_for_url_with_only_workspace() {
        // Given: URL with only workspace (missing repo)
        String url = "https://bitbucket.org/workspace";

        // When/Then: Verify IllegalArgumentException is thrown
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            BitbucketClient.parseRepoUrl(url);
        });

        assertTrue(exception.getMessage().contains("Could not extract workspace and repo"));
    }

    @Test
    void should_create_client_from_valid_repo_url() {
        // Given: Valid repo URL
        String url = "https://bitbucket.org/myworkspace/myrepo.git";

        // When: Create client from URL
        BitbucketClient client = BitbucketClient.fromRepoUrl(url, "username", "password");

        // Then: Verify client is created successfully
        assertNotNull(client);
    }

    @Test
    void should_throw_exception_when_creating_client_with_invalid_url() {
        // Given: Invalid URL
        String url = "invalid-url";

        // When/Then: Verify exception is thrown
        assertThrows(IllegalArgumentException.class, () -> {
            BitbucketClient.fromRepoUrl(url, "username", "password");
        });
    }
}
