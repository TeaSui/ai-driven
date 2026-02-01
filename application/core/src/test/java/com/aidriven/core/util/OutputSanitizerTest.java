package com.aidriven.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutputSanitizerTest {

    // --- PR Description ---

    @Test
    void should_return_default_for_null_pr_description() {
        assertEquals("Auto-generated code changes.", OutputSanitizer.sanitizePrDescription(null));
    }

    @Test
    void should_return_default_for_blank_pr_description() {
        assertEquals("Auto-generated code changes.", OutputSanitizer.sanitizePrDescription("  "));
    }

    @Test
    void should_pass_through_valid_pr_description() {
        String desc = "This PR fixes a bug in the login flow.";

        assertEquals(desc, OutputSanitizer.sanitizePrDescription(desc));
    }

    @Test
    void should_truncate_long_pr_description() {
        String longDesc = "x".repeat(15_000);

        String result = OutputSanitizer.sanitizePrDescription(longDesc);

        assertTrue(result.length() <= 10_000);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void should_remove_control_chars_from_description() {
        String withControl = "Hello\u0000World\u0007Test";

        String result = OutputSanitizer.sanitizePrDescription(withControl);

        assertEquals("HelloWorldTest", result);
    }

    @Test
    void should_preserve_newlines_and_tabs_in_description() {
        String text = "Line 1\nLine 2\n\tIndented";

        assertEquals(text, OutputSanitizer.sanitizePrDescription(text));
    }

    // --- PR Title ---

    @Test
    void should_return_default_for_null_title() {
        assertEquals("Auto-generated changes", OutputSanitizer.sanitizePrTitle(null));
    }

    @Test
    void should_return_default_for_blank_title() {
        assertEquals("Auto-generated changes", OutputSanitizer.sanitizePrTitle(""));
    }

    @Test
    void should_flatten_multiline_title_to_single_line() {
        String multiline = "First line\nSecond line\rThird line";

        String result = OutputSanitizer.sanitizePrTitle(multiline);

        assertFalse(result.contains("\n"));
        assertFalse(result.contains("\r"));
        assertTrue(result.contains("First line"));
        assertTrue(result.contains("Second line"));
    }

    @Test
    void should_truncate_long_title() {
        String longTitle = "A".repeat(300);

        String result = OutputSanitizer.sanitizePrTitle(longTitle);

        assertTrue(result.length() <= 200);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void should_remove_control_chars_from_title() {
        String withControl = "Fix\u0001Bug\u0002Here";

        String result = OutputSanitizer.sanitizePrTitle(withControl);

        assertEquals("FixBugHere", result);
    }

    // --- Commit Message ---

    @Test
    void should_return_default_for_null_commit_message() {
        assertEquals("feat: auto-generated changes", OutputSanitizer.sanitizeCommitMessage(null));
    }

    @Test
    void should_return_default_for_blank_commit_message() {
        assertEquals("feat: auto-generated changes", OutputSanitizer.sanitizeCommitMessage("  "));
    }

    @Test
    void should_pass_through_valid_commit_message() {
        String msg = "fix: resolve NPE in handler";

        assertEquals(msg, OutputSanitizer.sanitizeCommitMessage(msg));
    }

    @Test
    void should_truncate_long_commit_message() {
        String longMsg = "M".repeat(700);

        String result = OutputSanitizer.sanitizeCommitMessage(longMsg);

        assertTrue(result.length() <= 500);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void should_remove_control_chars_from_commit_message() {
        String withControl = "feat: add\u0000feature";

        String result = OutputSanitizer.sanitizeCommitMessage(withControl);

        assertEquals("feat: addfeature", result);
    }
}
