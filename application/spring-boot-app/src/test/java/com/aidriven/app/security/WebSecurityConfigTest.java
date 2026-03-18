package com.aidriven.app.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSecurityConfigTest {

    private final WebSecurityConfig config = new WebSecurityConfig();

    @Test
    void should_create_github_webhook_filter_with_hmac_mode() {
        HmacWebhookFilter filter = config.githubWebhookFilter("test-secret");

        assertThat(filter).isNotNull();
    }

    @Test
    void should_create_jira_webhook_filter_in_token_mode() {
        HmacWebhookFilter filter = config.jiraWebhookFilter("test-token");

        assertThat(filter).isNotNull();
    }

    @Test
    void should_create_bitbucket_webhook_filter_with_hmac_mode() {
        HmacWebhookFilter filter = config.bitbucketWebhookFilter("test-secret");

        assertThat(filter).isNotNull();
    }

    @Test
    void should_handle_empty_secret_for_github_filter() {
        HmacWebhookFilter filter = config.githubWebhookFilter("");

        assertThat(filter).isNotNull();
    }
}
