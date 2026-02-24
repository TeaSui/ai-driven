package com.aidriven.lambda.source;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.core.config.AppConfig;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.github.GitHubClient;
import com.aidriven.lambda.factory.ServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SourceControlClientResolver} — platform resolution from explicit
 * parameters and from Jira ticket labels.
 */
class SourceControlClientResolverTest {

    @Mock private ServiceFactory serviceFactory;
    @Mock private AppConfig       appConfig;
    @Mock private GitHubClient    mockGitHubClient;
    @Mock private BitbucketClient mockBitbucketClient;

    private SourceControlClientResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(serviceFactory.getAppConfig()).thenReturn(appConfig);
        when(appConfig.getDefaultPlatform()).thenReturn("BITBUCKET");
        when(appConfig.getDefaultWorkspace()).thenReturn("default-workspace");
        when(appConfig.getDefaultRepo()).thenReturn("default-repo");

        when(serviceFactory.getGitHubClient(any(), any())).thenReturn(mockGitHubClient);
        when(serviceFactory.getBitbucketClient(any(), any())).thenReturn(mockBitbucketClient);

        resolver = new SourceControlClientResolver(serviceFactory);
    }

    // ─── resolve(platform, owner, slug) — explicit params ───

    @Test
    void resolve_returns_github_client_for_GITHUB_platform() {
        SourceControlClient result = resolver.resolve("GITHUB", "myOrg", "myRepo");

        assertSame(mockGitHubClient, result);
        verify(serviceFactory).getGitHubClient("myOrg", "myRepo");
        verify(serviceFactory, never()).getBitbucketClient(any(), any());
    }

    @Test
    void resolve_returns_bitbucket_client_for_BITBUCKET_platform() {
        SourceControlClient result = resolver.resolve("BITBUCKET", "myWorkspace", "myRepo");

        assertSame(mockBitbucketClient, result);
        verify(serviceFactory).getBitbucketClient("myWorkspace", "myRepo");
        verify(serviceFactory, never()).getGitHubClient(any(), any());
    }

    @Test
    void resolve_is_case_insensitive_for_platform_string() {
        // lowercase "github" should resolve to GitHub
        assertSame(mockGitHubClient, resolver.resolve("github", "owner", "repo"));
    }

    @Test
    void resolve_falls_back_to_default_platform_when_platform_is_null() {
        // Default is BITBUCKET (set in setUp)
        SourceControlClient result = resolver.resolve(null, "owner", "repo");

        assertSame(mockBitbucketClient, result);
    }

    @Test
    void resolve_falls_back_to_default_platform_when_platform_is_blank() {
        SourceControlClient result = resolver.resolve("  ", "owner", "repo");

        assertSame(mockBitbucketClient, result);
    }

    // ─── resolveFromLabels — label-driven resolution ───

    @Test
    void resolveFromLabels_resolves_github_from_platform_label() {
        List<String> labels = List.of("platform:github", "some-other-label");

        SourceControlClient result = resolver.resolveFromLabels(labels);

        assertSame(mockGitHubClient, result);
        verify(serviceFactory).getGitHubClient(any(), any());
    }

    @Test
    void resolveFromLabels_resolves_repo_owner_and_slug_from_repo_label() {
        // "repo:acme/payments" label should override defaults
        List<String> labels = List.of("platform:github", "repo:acme/payments");

        resolver.resolveFromLabels(labels);

        verify(serviceFactory).getGitHubClient("acme", "payments");
    }

    @Test
    void resolveFromLabels_falls_back_to_default_platform_when_no_labels() {
        // Default is BITBUCKET
        SourceControlClient result = resolver.resolveFromLabels(List.of());

        assertSame(mockBitbucketClient, result);
    }

    @Test
    void resolveFromLabels_falls_back_to_default_platform_when_labels_null() {
        SourceControlClient result = resolver.resolveFromLabels(null);

        assertSame(mockBitbucketClient, result);
    }

    @Test
    void resolveFromLabels_uses_default_workspace_and_repo_when_no_repo_label() {
        // No "repo:" label — defaults should be used
        List<String> labels = List.of("platform:bitbucket");

        resolver.resolveFromLabels(labels);

        // BitbucketClient should be called with the defaults
        verify(serviceFactory).getBitbucketClient("default-workspace", "default-repo");
    }

    @Test
    void resolveFromLabels_with_github_default_delegates_to_github() {
        when(appConfig.getDefaultPlatform()).thenReturn("GITHUB");

        SourceControlClient result = resolver.resolveFromLabels(List.of());

        assertSame(mockGitHubClient, result);
    }
}
