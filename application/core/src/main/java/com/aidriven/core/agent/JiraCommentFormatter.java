package com.aidriven.core.agent;

import java.util.List;

/**
 * Converts Claude's markdown response to Jira wiki markup
 * and adds metadata footer.
 */
public class JiraCommentFormatter {

    private static final int MAX_COMMENT_LENGTH = 32_000;

    /**
     * Format a Claude response as a Jira comment.
     *
     * @param markdownText Claude's response in markdown
     * @param toolsUsed    List of tool names used during processing
     * @param tokenCount   Total tokens consumed
     * @return Jira-formatted comment
     */
    public String format(String markdownText, List<String> toolsUsed, int tokenCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(convertMarkdownToJira(markdownText));

        // Metadata footer
        sb.append("\n\n----\n");
        sb.append("_🤖 AI Agent");
        if (!toolsUsed.isEmpty()) {
            sb.append(" | Tools: ").append(String.join(", ", toolsUsed));
        }
        if (tokenCount > 0) {
            sb.append(" | Tokens: ").append(String.format("%,d", tokenCount));
        }
        sb.append("_");

        return truncate(sb.toString());
    }

    /** Format an ack (acknowledgment) comment. */
    public String formatAck(String userMessage) {
        String preview = userMessage.length() > 80
                ? userMessage.substring(0, 80) + "..."
                : userMessage;
        return "🤖 Processing your request...\n\n" +
                "{quote}" + preview + "{quote}\n\n" +
                "_Working on it — this comment will be updated with results._";
    }

    /** Format an error response. */
    public String formatError(String errorMessage) {
        return "🤖 ❌ *Error processing request*\n\n" +
                "{code}" + errorMessage + "{code}\n\n" +
                "_Please try again or contact the team._";
    }

    /**
     * Convert markdown to Jira wiki syntax.
     * Handles the most common patterns used by Claude.
     */
    String convertMarkdownToJira(String markdown) {
        if (markdown == null || markdown.isEmpty())
            return "";

        String result = markdown;

        // Code blocks: ```lang\n...\n``` → {code:lang}\n...\n{code}
        // Handles: with/without language, with/without trailing newline before closing ```
        result = result.replaceAll("```(\\w+)?\\s*\\n([\\s\\S]*?)\\n?```", "{code:$1}\n$2\n{code}");
        // Clean up no-language case: {code:} → {code}
        result = result.replace("{code:}", "{code}");

        // Inline code: `text` → {{text}} (but not inside {code} blocks)
        result = result.replaceAll("`([^`]+)`", "{{$1}}");

        // Bold: **text** → *text*
        result = result.replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*");

        // Headers: ### → h3. (process deepest first to avoid double-matching)
        result = result.replaceAll("(?m)^### (.+)$", "h3. $1");
        result = result.replaceAll("(?m)^## (.+)$", "h2. $1");
        result = result.replaceAll("(?m)^# (.+)$", "h1. $1");

        // Bullet lists: - item → * item (supports two levels of nesting)
        result = result.replaceAll("(?m)^  - (.+)$", "** $1");
        result = result.replaceAll("(?m)^- (.+)$", "* $1");

        // Numbered lists: 1. item → # item
        result = result.replaceAll("(?m)^\\d+\\. (.+)$", "# $1");

        return result;
    }

    private String truncate(String text) {
        if (text.length() <= MAX_COMMENT_LENGTH)
            return text;
        return text.substring(0, MAX_COMMENT_LENGTH - 100)
                + "\n\n_... [response truncated at " + MAX_COMMENT_LENGTH + " chars]_";
    }
}
