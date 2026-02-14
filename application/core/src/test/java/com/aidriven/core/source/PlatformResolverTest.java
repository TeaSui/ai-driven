package com.aidriven.core.source;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlatformResolverTest {

    @Nested
    class ResolveFromLabels {
        @Test
        void should_resolve_github_from_label() {
            Platform result = PlatformResolver.resolve(
                    List.of("ai-generate", "platform:github"), null, null);
            assertEquals(Platform.GITHUB, result);
        }

        @Test
        void should_resolve_bitbucket_from_label() {
            Platform result = PlatformResolver.resolve(
                    List.of("platform:bitbucket"), null, null);
            assertEquals(Platform.BITBUCKET, result);
        }

        @Test
        void should_be_case_insensitive() {
            Platform result = PlatformResolver.resolve(
                    List.of("Platform:GitHub"), null, null);
            assertEquals(Platform.GITHUB, result);
        }

        @Test
        void should_ignore_invalid_platform_label() {
            Platform result = PlatformResolver.resolve(
                    List.of("platform:gitlab"), null, "BITBUCKET");
            assertEquals(Platform.BITBUCKET, result);
        }
    }

    @Nested
    class ResolveFromUrl {
        @Test
        void should_detect_github_from_url() {
            Platform result = PlatformResolver.resolve(
                    null, "https://github.com/owner/repo", null);
            assertEquals(Platform.GITHUB, result);
        }

        @Test
        void should_detect_bitbucket_from_url() {
            Platform result = PlatformResolver.resolve(
                    null, "https://bitbucket.org/workspace/repo", null);
            assertEquals(Platform.BITBUCKET, result);
        }

        @Test
        void should_detect_bitbucket_from_ssh_url() {
            Platform result = PlatformResolver.resolve(
                    null, "git@bitbucket.org:workspace/repo.git", null);
            assertEquals(Platform.BITBUCKET, result);
        }
    }

    @Nested
    class ResolveFromDefault {
        @Test
        void should_use_default_platform() {
            Platform result = PlatformResolver.resolve(null, null, "GITHUB");
            assertEquals(Platform.GITHUB, result);
        }

        @Test
        void should_use_bitbucket_as_ultimate_fallback() {
            Platform result = PlatformResolver.resolve(null, null, null);
            assertEquals(Platform.BITBUCKET, result);
        }

        @Test
        void should_fallback_on_blank_default() {
            Platform result = PlatformResolver.resolve(null, null, "");
            assertEquals(Platform.BITBUCKET, result);
        }
    }

    @Nested
    class LabelPriorityOverUrl {
        @Test
        void should_prefer_label_over_url() {
            Platform result = PlatformResolver.resolve(
                    List.of("platform:github"),
                    "https://bitbucket.org/workspace/repo",
                    "BITBUCKET");
            assertEquals(Platform.GITHUB, result);
        }

        @Test
        void should_prefer_url_over_default() {
            Platform result = PlatformResolver.resolve(
                    List.of("ai-generate"),
                    "https://github.com/owner/repo",
                    "BITBUCKET");
            assertEquals(Platform.GITHUB, result);
        }
    }

    @Nested
    class PlatformEnum {
        @Test
        void should_parse_github_string() {
            assertEquals(Platform.GITHUB, Platform.fromString("github"));
            assertEquals(Platform.GITHUB, Platform.fromString("GITHUB"));
            assertEquals(Platform.GITHUB, Platform.fromString("  GitHub  "));
        }

        @Test
        void should_return_null_for_invalid() {
            assertNull(Platform.fromString("gitlab"));
            assertNull(Platform.fromString(null));
            assertNull(Platform.fromString(""));
        }

        @Test
        void should_detect_platform_from_url() {
            assertEquals(Platform.GITHUB, Platform.fromUrl("https://github.com/owner/repo"));
            assertEquals(Platform.BITBUCKET, Platform.fromUrl("https://bitbucket.org/ws/repo"));
            assertNull(Platform.fromUrl("https://gitlab.com/owner/repo"));
            assertNull(Platform.fromUrl(null));
        }
    }
}
