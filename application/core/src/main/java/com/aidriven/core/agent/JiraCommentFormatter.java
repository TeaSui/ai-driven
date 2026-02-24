package com.aidriven.core.agent;

import java.util.List;

/**
 * Converts Claude's markdown response to Jira wiki markup and adds
 * a metadata footer. Optionally prepends a Jira @-mention of the original
 * comment author so they receive a notification.
 */
public class JiraCommentFormatter {

    private static final int MAX_COMMENT_LENGTH = 32_000;

    // ------------------------------------------------------------------
    // Primary formatting methods
    // ------------------------------------------------------------------

    /**
     * Format a Claude response as a Jira comment, mentioning the original author.
     *
     * @param markdownText    Claude's response in markdown
     * @param toolsUsed       List of tool names used during processing
     * @param tokenCount      Total tokens consumed
     * @param authorAccountId Jira accountId of the user who triggered the agent
     *                        (nullable — skips mention if null)
     */
    public String format(String markdownText, List<String> toolsUsed, int tokenCount,
            String authorAccountId) {
        StringBuilder sb = new StringBuilder();
        if (authorAccountId != null && !authorAccountId.isBlank()) {
            sb.append("[~accountId:").append(authorAccountId).append("] ");
        }
        sb.append(convertMarkdownToJira(markdownText));
        appendFooter(sb, toolsUsed, tokenCount);
        return truncate(sb.toString());
    }

    /**
     * Format a Claude response without an author mention (backward-compatible overload).
     */
    public String format(String markdownText, List<String> toolsUsed, int tokenCount) {
        return format(markdownText, toolsUsed, tokenCount, null);
    }

    /**
     * Format an acknowledgment comment, optionally mentioning the original author.
     *
     * @param userMessage     The stripped user request (used as a preview)
     * @param authorAccountId Jira accountId to mention (nullable)
     */
    public String formatAck(String userMessage, String authorAccountId) {
        String preview = userMessage != null && userMessage.length() > 80
                ? userMessage.substring(0, 80) + "..."
                : userMessage;

        StringBuilder sb = new StringBuilder();
        if (authorAccountId != null && !authorAccountId.isBlank()) {
            sb.append("[~accountId:").append(authorAccountId).append("] ");
        }
        sb.append("🤖 Processing your request...\n\n");
        if (preview != null && !preview.isBlank()) {
            sb.append("{quote}").append(preview).append("{quote}\n\n");
        }
        sb.append("_Working on it — this comment will be updated with results._");
        return sb.toString();
    }

    /** Format an ack comment without an author mention (backward-compatible overload). */
    public String formatAck(String userMessage) {
        return formatAck(userMessage, null);
    }

    /**
     * Format a response as a reply to the original comment.
     * Includes a quoted excerpt of the parent comment and @-mentions the original author.
     *
     * @param responseText           Claude's response text (markdown)
     * @param parentExcerpt          Excerpt from the parent comment to quote (truncated to 200 chars)
     * @param parentAuthorAccountId  Jira accountId of the original comment author
     * @param toolsUsed              List of tool names used during processing
     * @param tokenCount             Total tokens consumed
     */
    public String formatAsReply(String responseText, String parentExcerpt, String parentAuthorAccountId,
            List<String> toolsUsed, int tokenCount) {
        StringBuilder sb = new StringBuilder();

        // Quote parent comment (truncated to 200 chars)
        if (parentExcerpt != null && !parentExcerpt.isBlank()) {
            String truncated = parentExcerpt.length() > 200
                    ? parentExcerpt.substring(0, 197) + "..."
                    : parentExcerpt;
            sb.append("{quote}").append(truncated).append("{quote}\n\n");
        }

        // Mention original author
        if (parentAuthorAccountId != null && !parentAuthorAccountId.isBlank()) {
            sb.append("[~accountId:").append(parentAuthorAccountId).append("] ");
        }

        // Response body
        sb.append(convertMarkdownToJira(responseText));

        // Footer
        appendFooter(sb, toolsUsed, tokenCount);

        return truncate(sb.toString());
    }

    /** Format an error response. */
    public String formatError(String errorMessage) {
        return "🤖 ❌ *Error processing request*\n\n" +
                "{code}" + errorMessage + "{code}\n\n" +
                "_Please try again or contact the team._";
    }

    // ------------------------------------------------------------------
    // Markdown → Jira wiki markup conversion
    // ------------------------------------------------------------------

    /**
     * Convert markdown to Jira wiki syntax.
     * Handles the most common patterns used by Claude.
     */
    String convertMarkdownToJira(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        String result = markdown;

        // Code blocks: ```lang\n...\n``` → {code:lang}\n...\n{code}
        result = result.replaceAll("```(\\w+)?\\s*\\n([\\s\\S]*?)\\n?```", "{code:$1}\n$2\n{code}");
        result = result.replace("{code:}", "{code}");

        // Inline code: `text` → {{text}}
        result = result.replaceAll("`([^`]+)`", "{{$1}}");

        // Bold: **text** → *text*
        result = result.replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*");

        // Headers (deepest first to avoid double-matching)
        result = result.replaceAll("(?m)^### (.+)$", "h3. $1");
        result = result.replaceAll("(?m)^## (.+)$", "h2. $1");
        result = result.replaceAll("(?m)^# (.+)$", "h1. $1");

        // Bullet lists — two levels of nesting
        result = result.replaceAll("(?m)^  - (.+)$", "** $1");
        result = result.replaceAll("(?m)^- (.+)$", "* $1");

        // Numbered lists
        result = result.replaceAll("(?m)^\\d+\\. (.+)$", "# $1");

        return result;
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private void appendFooter(StringBuilder sb, List<String> toolsUsed, int tokenCount) {
        sb.append("\n\n----\n");
        sb.append("_🤖 AI Agent");
        if (toolsUsed != null && !toolsUsed.isEmpty()) {
            sb.append(" | Tools: ").append(String.join(", ", toolsUsed));
        }
        if (tokenCount > 0) {
            sb.append(" | Tokens: ").append(String.format("%,d", tokenCount));
        }
        sb.append("_");
    }

    private String truncate(String text) {
        if (text.length() <= MAX_COMMENT_LENGTH) return text;
        return text.substring(0, MAX_COMMENT_LENGTH - 100)
                + "\n\n_... [response truncated at " + MAX_COMMENT_LENGTH + " chars]_";
    }
}
