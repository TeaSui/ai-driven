package com.aidriven.core.source;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryResolverTest {

    @Nested
    class ResolveFromLabels {
        @Test
        void should_resolve_repo_from_label() {
            var result = RepositoryResolver.resolve(
                    List.of("ai-generate", "repo:my-org/my-repo"), null, null, null, null);

            assertNotNull(result);
            assertEquals("my-org", result.owner());
            assertEquals("my-repo", result.repo());
        }

        @Test
        void should_resolve_platform_alongside_repo_label() {
            var result = RepositoryResolver.resolve(
                    List.of("repo:owner/project", "platform:github"), null, null, null, null);

            assertNotNull(result);
            assertEquals("owner", result.owner());
            assertEquals("project", result.repo());
            assertEquals(Platform.GITHUB, result.platform());
        }

        @Test
        void should_ignore_malformed_repo_label() {
            var result = RepositoryResolver.resolve(
                    List.of("repo:invalid"), null, "default-ws", "default-repo", null);

            assertNotNull(result);
            assertEquals("default-ws", result.owner());
            assertEquals("default-repo", result.repo());
        }

        @Test
        void should_ignore_empty_repo_label() {
            var result = RepositoryResolver.resolve(
                    List.of("repo:/repo"), null, "ws", "repo", null);

            assertNotNull(result);
            assertEquals("ws", result.owner());
        }
    }

    @Nested
    class ResolveFromUrl {
        @Test
        void should_resolve_from_github_url() {
            var result = RepositoryResolver.resolve(
                    null, "https://github.com/acme/backend", null, null, null);

            assertNotNull(result);
            assertEquals("acme", result.owner());
            assertEquals("backend", result.repo());
            assertEquals(Platform.GITHUB, result.platform());
        }

        @Test
        void should_resolve_from_bitbucket_url() {
            var result = RepositoryResolver.resolve(
                    null, "https://bitbucket.org/workspace/project", null, null, null);

            assertNotNull(result);
            assertEquals("workspace", result.owner());
            assertEquals("project", result.repo());
            assertEquals(Platform.BITBUCKET, result.platform());
        }

        @Test
        void should_resolve_from_ssh_url() {
            var result = RepositoryResolver.resolve(
                    null, "git@github.com:owner/repo.git", null, null, null);

            assertNotNull(result);
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
            assertEquals(Platform.GITHUB, result.platform());
        }
    }

    @Nested
    class ResolveFromDefaults {
        @Test
        void should_use_default_owner_and_repo() {
            var result = RepositoryResolver.resolve(
                    null, null, "my-workspace", "my-repo", "BITBUCKET");

            assertNotNull(result);
            assertEquals("my-workspace", result.owner());
            assertEquals("my-repo", result.repo());
            assertEquals(Platform.BITBUCKET, result.platform());
        }

        @Test
        void should_return_null_when_no_info_available() {
            var result = RepositoryResolver.resolve(null, null, null, null, null);
            assertNull(result);
        }

        @Test
        void should_return_null_when_only_owner_provided() {
            var result = RepositoryResolver.resolve(null, null, "owner", null, null);
            assertNull(result);
        }

        @Test
        void should_return_null_when_only_repo_provided() {
            var result = RepositoryResolver.resolve(null, null, null, "repo", null);
            assertNull(result);
        }
    }

    @Nested
    class LabelPriority {
        @Test
        void should_prefer_label_over_url() {
            var result = RepositoryResolver.resolve(
                    List.of("repo:label-owner/label-repo"),
                    "https://github.com/url-owner/url-repo",
                    "default-owner", "default-repo", null);

            assertNotNull(result);
            assertEquals("label-owner", result.owner());
            assertEquals("label-repo", result.repo());
        }

        @Test
        void should_prefer_url_over_defaults() {
            var result = RepositoryResolver.resolve(
                    List.of("ai-generate"),
                    "https://github.com/url-owner/url-repo",
                    "default-owner", "default-repo", null);

            assertNotNull(result);
            assertEquals("url-owner", result.owner());
            assertEquals("url-repo", result.repo());
        }
    }
}
