package com.aidriven.lambda.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookValidatorTest {

    @Test
    void should_validate_valid_github_signature() {
        String secret = "my_secret";
        String body = "{\"action\":\"opened\"}";
        // Computed HMAC-SHA256 for body "{\"action\":\"opened\"}" with key "my_secret":
        String expectedHash = "sha256=85c3db363954b34197ef5e5c504f7ef15f2a69fdad5d75e441a4f7aa08ca51df";

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Hub-Signature-256", expectedHash);

        assertDoesNotThrow(() -> WebhookValidator.verifyGithubSignature(headers, body, secret));
    }

    @Test
    void should_throw_on_invalid_github_signature() {
        String secret = "my_secret";
        String body = "{\"action\":\"opened\"}";
        String invalidHash = "sha256=badhashvalue";

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Hub-Signature-256", invalidHash);

        SecurityException exception = assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyGithubSignature(headers, body, secret));
        assertTrue(exception.getMessage().contains("GitHub signature verification failed"));
    }

    @Test
    void should_validate_valid_bitbucket_signature() {
        String secret = "bitbucket_secret";
        String body = "{\"push\":{}}";
        // Computed HMAC-SHA256 for "{\"push\":{}}" with "bitbucket_secret":
        String expectedHash = "sha256=811ba2c844d0e540744fb76525515efc31131584fade68554c63e7963c68bbd5";

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Hub-Signature", expectedHash);

        assertDoesNotThrow(() -> WebhookValidator.verifyBitbucketSignature(headers, body, secret));
    }

    @Test
    void should_throw_on_invalid_bitbucket_signature() {
        String secret = "bitbucket_secret";
        String body = "{\"push\":{}}";
        String invalidHash = "sha256=aaaaaaaaaaaaaaaaaaaaaaaaa";

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Hub-Signature", invalidHash);

        SecurityException exception = assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyBitbucketSignature(headers, body, secret));
        assertTrue(exception.getMessage().contains("Bitbucket signature verification failed"));
    }

    @Test
    void should_validate_valid_jira_token() {
        String secret = "jira-token-123";
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer jira-token-123");

        assertDoesNotThrow(() -> WebhookValidator.verifyJiraWebhookToken(headers, secret));
    }

    @Test
    void should_throw_on_invalid_jira_token() {
        String secret = "jira-token-123";
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer bad-token");

        SecurityException exception = assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(headers, secret));
        assertTrue(exception.getMessage().contains("Jira webhook token verification failed"));
    }
}
