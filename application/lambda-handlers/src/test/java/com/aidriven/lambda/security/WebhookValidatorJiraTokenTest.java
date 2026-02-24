package com.aidriven.lambda.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WebhookValidator#verifyJiraWebhookToken} — the Jira
 * pre-shared token verification path.
 *
 * <p>Security contract:
 * <ul>
 *   <li>Exact match required; comparison is constant-time to prevent timing attacks</li>
 *   <li>Accepts token in {@code X-Jira-Webhook-Token} or {@code Authorization: Bearer} header</li>
 *   <li>Silently skips (WARN-logs) when no secret is configured — opt-in enforcement</li>
 * </ul>
 */
class WebhookValidatorJiraTokenTest {

    private static final String VALID_TOKEN = "super-secret-jira-token-abc123";

    // ─── Skip-when-unconfigured ───

    @Test
    void should_skip_verification_when_expected_token_is_null() {
        // Must NOT throw — operators who haven't configured the secret yet should not be locked out
        assertDoesNotThrow(() ->
                WebhookValidator.verifyJiraWebhookToken(Map.of("x-jira-webhook-token", "anything"), null));
    }

    @Test
    void should_skip_verification_when_expected_token_is_blank() {
        assertDoesNotThrow(() ->
                WebhookValidator.verifyJiraWebhookToken(Map.of("x-jira-webhook-token", "anything"), "   "));
    }

    @Test
    void should_skip_verification_when_expected_token_is_empty() {
        assertDoesNotThrow(() ->
                WebhookValidator.verifyJiraWebhookToken(Map.of("x-jira-webhook-token", "anything"), ""));
    }

    // ─── Valid token paths ───

    @Test
    void should_pass_verification_for_matching_x_jira_webhook_token_header() {
        Map<String, String> headers = Map.of("x-jira-webhook-token", VALID_TOKEN);
        assertDoesNotThrow(() -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));
    }

    @Test
    void should_pass_verification_case_insensitive_header_name() {
        Map<String, String> headers = Map.of("X-Jira-Webhook-Token", VALID_TOKEN);
        assertDoesNotThrow(() -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));
    }

    @Test
    void should_pass_verification_for_bearer_authorization_header() {
        Map<String, String> headers = Map.of("Authorization", "Bearer " + VALID_TOKEN);
        assertDoesNotThrow(() -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));
    }

    @Test
    void should_pass_verification_for_lowercase_authorization_header() {
        Map<String, String> headers = Map.of("authorization", "Bearer " + VALID_TOKEN);
        assertDoesNotThrow(() -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));
    }

    // ─── Rejection paths ───

    @Test
    void should_throw_when_token_header_is_missing() {
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        SecurityException ex = assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));

        assertTrue(ex.getMessage().toLowerCase().contains("missing"),
                "Exception should mention 'missing', got: " + ex.getMessage());
    }

    @Test
    void should_throw_when_token_does_not_match() {
        Map<String, String> headers = Map.of("x-jira-webhook-token", "wrong-token");

        SecurityException ex = assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));

        assertTrue(ex.getMessage().toLowerCase().contains("verif"),
                "Exception should mention 'verification', got: " + ex.getMessage());
    }

    @Test
    void should_throw_when_bearer_token_does_not_match() {
        Map<String, String> headers = Map.of("Authorization", "Bearer wrong-token");

        assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));
    }

    @Test
    void should_throw_when_headers_are_null() {
        assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(null, VALID_TOKEN));
    }

    @Test
    void should_throw_when_headers_map_is_empty() {
        assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(Map.of(), VALID_TOKEN));
    }

    // ─── Security: token must not be prefix-matchable ───

    @Test
    void should_reject_token_that_is_prefix_of_valid_token() {
        // "super-secret" is a prefix of VALID_TOKEN but must not pass
        Map<String, String> headers = Map.of("x-jira-webhook-token", "super-secret");
        assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));
    }

    @Test
    void should_reject_token_that_is_suffix_of_valid_token() {
        Map<String, String> headers = Map.of("x-jira-webhook-token", "abc123");
        assertThrows(SecurityException.class,
                () -> WebhookValidator.verifyJiraWebhookToken(headers, VALID_TOKEN));
    }
}
