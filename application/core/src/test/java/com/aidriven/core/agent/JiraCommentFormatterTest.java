package com.aidriven.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JiraCommentFormatterTest {

    private JiraCommentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new JiraCommentFormatter();
    }

    // ─── Response Formatting ───

    @Test
    void should_format_response_with_metadata_footer() {
        String result = formatter.format("Hello world", List.of("source_control_get_file"), 1500);

        assertTrue(result.contains("Hello world"));
        assertTrue(result.contains("Tools: source_control_get_file"));
        assertTrue(result.contains("Tokens: 1,500"));
        assertTrue(result.contains("🤖 AI Agent"));
    }

    @Test
    void should_format_response_without_tools() {
        String result = formatter.format("Just text", List.of(), 500);

        assertTrue(result.contains("Just text"));
        assertFalse(result.contains("Tools:"));
    }

    // ─── Ack Comment ───

    @Test
    void should_format_ack_comment() {
        String result = formatter.formatAck("fix the NPE");

        assertTrue(result.contains("🤖 Processing"));
        assertTrue(result.contains("fix the NPE"));
    }

    @Test
    void should_truncate_long_ack_preview() {
        String longMsg = "x".repeat(200);
        String result = formatter.formatAck(longMsg);

        assertTrue(result.contains("..."));
    }

    @Test
    void should_format_ack_with_author_account_id_prepends_mention() {
        String result = formatter.formatAck("fix the NPE", "5a2b3c4d5e6f7a8b9c0d");

        // Jira @-mention format: [~accountId:xxx]
        assertTrue(result.contains("[~accountId:5a2b3c4d5e6f7a8b9c0d]"),
                "formatAck with accountId should prepend Jira mention, got: " + result);
        assertTrue(result.contains("fix the NPE"));
        assertTrue(result.contains("🤖 Processing"));
    }

    @Test
    void should_format_ack_with_null_account_id_omits_mention() {
        String result = formatter.formatAck("fix the NPE", null);

        assertFalse(result.contains("[~accountId:"),
                "formatAck with null accountId must not include mention");
        assertTrue(result.contains("🤖 Processing"));
        assertTrue(result.contains("fix the NPE"));
    }

    @Test
    void should_format_ack_with_blank_account_id_omits_mention() {
        String result = formatter.formatAck("do something", "  ");

        assertFalse(result.contains("[~accountId:"),
                "formatAck with blank accountId must not include mention");
    }

    @Test
    void single_arg_formatAck_is_backward_compatible_with_two_arg_null_variant() {
        String oneArg = formatter.formatAck("check the logs");
        String twoArg = formatter.formatAck("check the logs", null);

        assertEquals(oneArg, twoArg,
                "Single-arg formatAck should produce same result as formatAck(msg, null)");
    }

    // ─── Error Formatting ───

    @Test
    void should_format_error_comment() {
        String result = formatter.formatError("Connection timeout");

        assertTrue(result.contains("❌"));
        assertTrue(result.contains("Connection timeout"));
    }

    // ─── Markdown → Jira Conversion ───

    @Test
    void should_convert_headers() {
        assertEquals("h1. Title", formatter.convertMarkdownToJira("# Title"));
        assertEquals("h2. Section", formatter.convertMarkdownToJira("## Section"));
        assertEquals("h3. Sub", formatter.convertMarkdownToJira("### Sub"));
    }

    @Test
    void should_convert_bold() {
        assertEquals("*bold text*", formatter.convertMarkdownToJira("**bold text**"));
    }

    @Test
    void should_convert_inline_code() {
        assertEquals("{{code}}", formatter.convertMarkdownToJira("`code`"));
    }

    @Test
    void should_convert_bullet_lists() {
        assertEquals("* item one", formatter.convertMarkdownToJira("- item one"));
    }

    @Test
    void should_convert_code_blocks() {
        String md = "```java\nSystem.out.println();\n```";
        String result = formatter.convertMarkdownToJira(md);
        assertTrue(result.contains("{code:java}"));
        assertTrue(result.contains("{code}"));
    }

    // ─── Truncation ───

    @Test
    void should_truncate_oversized_comment() {
        String longText = "x".repeat(40_000);
        String result = formatter.format(longText, List.of(), 0);

        assertTrue(result.length() < 33_000);
        assertTrue(result.contains("[response truncated"));
    }
}
