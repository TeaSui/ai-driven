package com.aidriven.lambda.security;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Input validation and sanitization helper for incoming webhook payloads.
 *
 * <p>
 * All untrusted input that flows into external API calls (GitHub, Jira) or
 * database keys must pass through this validator to prevent:
 * <ul>
 * <li>Path-traversal via malicious {@code repoOwner} / {@code repoSlug}
 * values</li>
 * <li>Injection of oversized payloads that could cause OOM or abuse</li>
 * <li>Blank/null fields that would cause downstream NPEs</li>
 * </ul>
 */
@Slf4j
public final class WebhookValidator {

    // GitHub repository owner/name: alphanumeric, hyphens, underscores, dots (max
    // 100 chars)
    private static final Pattern SAFE_REPO_IDENTIFIER = Pattern.compile("^[\\w.\\-]{1,100}$");

    // Numeric PR/comment IDs
    private static final Pattern NUMERIC_ID = Pattern.compile("^\\d{1,20}$");

    // Maximum lengths
    private static final int MAX_COMMENT_BODY_LENGTH = 65_536; // 64 KB
    private static final int MAX_TICKET_KEY_LENGTH = 64;
    private static final int MAX_AUTHOR_LENGTH = 256;
    private static final int MAX_DIFF_HUNK_LENGTH = 16_384;

    private WebhookValidator() {
    }

    /**
     * Validates and sanitizes a GitHub repository owner string.
     *
     * @throws SecurityException if the value is unsafe
     */
    public static String validateRepoOwner(String value) {
        return validateRepoIdentifier(value, "repoOwner");
    }

    /**
     * Validates and sanitizes a GitHub repository name.
     *
     * @throws SecurityException if the value is unsafe
     */
    public static String validateRepoSlug(String value) {
        return validateRepoIdentifier(value, "repoSlug");
    }

    /**
     * Validates a numeric comment/PR ID for GitHub.
     *
     * @throws SecurityException if the value is not a safe numeric string
     */
    public static String validateNumericId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new SecurityException("Webhook field '" + fieldName + "' must not be blank");
        }
        if (!NUMERIC_ID.matcher(value).matches()) {
            log.warn("Rejected unsafe numeric ID for field '{}': length={}", fieldName, value.length());
            throw new SecurityException(
                    "Webhook field '" + fieldName + "' must be a positive numeric ID");
        }
        return value;
    }

    /**
     * Truncates a comment body to a safe maximum length.
     * Never throws; oversized values are truncated and logged.
     */
    public static String sanitizeCommentBody(String value) {
        return truncate(value, MAX_COMMENT_BODY_LENGTH, "commentBody");
    }

    /** Truncates a diff hunk to a safe maximum length. */
    public static String sanitizeDiffHunk(String value) {
        return truncate(value, MAX_DIFF_HUNK_LENGTH, "diffHunk");
    }

    /** Validates a Jira ticket key (e.g. PROJ-123). */
    public static String validateTicketKey(String value) {
        if (value == null || value.isBlank()) {
            throw new SecurityException("ticketKey must not be blank");
        }
        if (value.length() > MAX_TICKET_KEY_LENGTH) {
            throw new SecurityException("ticketKey exceeds maximum length: " + value.length());
        }
        return value.strip();
    }

    /** Sanitizes a comment author display name. */
    public static String sanitizeAuthor(String value) {
        return truncate(value, MAX_AUTHOR_LENGTH, "commentAuthor");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String validateRepoIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new SecurityException("Webhook field '" + fieldName + "' must not be blank");
        }
        if (!SAFE_REPO_IDENTIFIER.matcher(value).matches()) {
            log.warn("Rejected unsafe repo identifier for field '{}': length={}", fieldName, value.length());
            throw new SecurityException(
                    "Webhook field '" + fieldName + "' contains disallowed characters or exceeds maximum length");
        }
        return value;
    }

    private static String truncate(String value, int maxLen, String fieldName) {
        if (value == null)
            return null;
        if (value.length() > maxLen) {
            log.warn("Truncating oversized '{}' field from {} to {} chars", fieldName, value.length(), maxLen);
            return value.substring(0, maxLen);
        }
        return value;
    }

    /**
     * Verifies the GitHub webhook signature using HMAC-SHA256.
     *
     * @param headers The HTTP headers from the request
     * @param body    The raw request body
     * @param secret  The webhook secret configured in GitHub
     * @throws SecurityException if verification fails
     */
    public static void verifyGithubSignature(Map<String, String> headers, String body, String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("GitHub webhook secret is not configured, skipping signature verification.");
            return;
        }

        String signatureHeader = null;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if ("x-hub-signature-256".equalsIgnoreCase(entry.getKey())) {
                    signatureHeader = entry.getValue();
                    break;
                }
            }
        }

        if (signatureHeader == null) {
            throw new SecurityException("Missing X-Hub-Signature-256 header from GitHub webhook request");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hashBytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));

            StringBuilder hashString = new StringBuilder("sha256=");
            for (byte b : hashBytes) {
                hashString.append(String.format("%02x", b));
            }

            if (!MessageDigest.isEqual(hashString.toString().getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("GitHub signature verification failed");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Failed to verify GitHub signature: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a Jira webhook using a pre-shared token in the Authorization header.
     *
     * <p>
     * Jira Cloud webhook requests can be configured with a secret token that is
     * sent as {@code Authorization: Bearer {token}} or
     * {@code X-Jira-Webhook-Token}.
     * This method checks both header locations using a constant-time comparison.
     *
     * @param headers       HTTP headers from the request (case-insensitive lookup)
     * @param expectedToken Pre-shared token value stored in Secrets Manager
     * @throws SecurityException if the token is missing or does not match
     */
    public static void verifyJiraWebhookToken(Map<String, String> headers, String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            // Secret not configured — skip verification (not enforced)
            log.warn("Jira webhook secret not configured; skipping token verification");
            return;
        }

        String received = null;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                if ("x-jira-webhook-token".equalsIgnoreCase(key)) {
                    received = entry.getValue();
                    break;
                }
                if ("authorization".equalsIgnoreCase(key)) {
                    String val = entry.getValue();
                    if (val != null && val.toLowerCase().startsWith("bearer ")) {
                        received = val.substring(7).strip();
                    }
                }
            }
        }

        if (received == null || received.isBlank()) {
            throw new SecurityException(
                    "Missing Jira webhook token: expected X-Jira-Webhook-Token or Authorization header");
        }

        if (!MessageDigest.isEqual(
                received.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Jira webhook token verification failed");
        }
    }

    /**
     * Verifies the Bitbucket Cloud webhook signature using HMAC-SHA256.
     * Bitbucket sends the signature in the X-Hub-Signature header.
     *
     * @param headers The HTTP headers from the request
     * @param body    The raw request body
     * @param secret  The webhook secret configured in Bitbucket
     * @throws SecurityException if verification fails
     */
    public static void verifyBitbucketSignature(Map<String, String> headers, String body, String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("Bitbucket webhook secret is not configured, skipping signature verification.");
            return;
        }

        String signatureHeader = null;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if ("x-hub-signature".equalsIgnoreCase(entry.getKey())) {
                    signatureHeader = entry.getValue();
                    break;
                }
            }
        }

        if (signatureHeader == null) {
            throw new SecurityException("Missing X-Hub-Signature header from Bitbucket webhook request");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hashBytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));

            StringBuilder hashString = new StringBuilder("sha256=");
            for (byte b : hashBytes) {
                hashString.append(String.format("%02x", b));
            }

            if (!MessageDigest.isEqual(hashString.toString().getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("Bitbucket signature verification failed");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Failed to verify Bitbucket signature: " + e.getMessage(), e);
        }
    }
}
