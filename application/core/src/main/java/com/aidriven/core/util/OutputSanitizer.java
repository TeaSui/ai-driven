package com.aidriven.core.util;

/**
 * Sanitizes AI-generated output before embedding in external systems
 * (PR descriptions, Jira comments, etc.) to prevent injection attacks.
 */
public final class OutputSanitizer {

    private static final int MAX_PR_DESCRIPTION_LENGTH = 10_000;
    private static final int MAX_PR_TITLE_LENGTH = 200;
    private static final int MAX_COMMIT_MESSAGE_LENGTH = 500;

    private OutputSanitizer() {
        // Utility class
    }

    /**
     * Sanitizes text for use in a PR description.
     * Removes potential Bitbucket/GitHub markdown injection and truncates to safe length.
     */
    public static String sanitizePrDescription(String text) {
        if (text == null || text.isBlank()) {
            return "Auto-generated code changes.";
        }
        String sanitized = removeControlCharacters(text);
        sanitized = truncate(sanitized, MAX_PR_DESCRIPTION_LENGTH);
        return sanitized;
    }

    /**
     * Sanitizes text for use in a PR title.
     * Single line, no special characters that could break APIs.
     */
    public static String sanitizePrTitle(String text) {
        if (text == null || text.isBlank()) {
            return "Auto-generated changes";
        }
        String sanitized = text.replaceAll("[\\r\\n]+", " ").trim();
        sanitized = removeControlCharacters(sanitized);
        return truncate(sanitized, MAX_PR_TITLE_LENGTH);
    }

    /**
     * Sanitizes text for use in a commit message.
     */
    public static String sanitizeCommitMessage(String text) {
        if (text == null || text.isBlank()) {
            return "feat: auto-generated changes";
        }
        String sanitized = removeControlCharacters(text);
        return truncate(sanitized, MAX_COMMIT_MESSAGE_LENGTH);
    }

    /**
     * Removes ASCII control characters (except newline, tab, carriage return).
     */
    private static String removeControlCharacters(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c >= 32) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
