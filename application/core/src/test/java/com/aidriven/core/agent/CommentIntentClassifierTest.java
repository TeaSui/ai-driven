package com.aidriven.core.agent;

import com.aidriven.core.agent.model.CommentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CommentIntentClassifierTest {

    private CommentIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new CommentIntentClassifier();
    }

    // ─── AI Commands ───

    @Test
    void should_classify_ai_mention_as_command() {
        assertEquals(CommentIntent.AI_COMMAND, classifier.classify("@ai fix the NPE in UserService", false));
    }

    @Test
    void should_classify_ai_mention_case_insensitive() {
        assertEquals(CommentIntent.AI_COMMAND, classifier.classify("@AI fix this", false));
        assertEquals(CommentIntent.AI_COMMAND, classifier.classify("@Ai do something", false));
    }

    @Test
    void should_classify_ai_mention_with_leading_whitespace() {
        assertEquals(CommentIntent.AI_COMMAND, classifier.classify("  @ai fix this", false));
    }

    // ─── Questions ───

    @ParameterizedTest
    @ValueSource(strings = {
            "@ai why is this endpoint slow?",
            "@ai how do I fix this?",
            "@ai what caused the outage?",
            "@ai can you explain the error?"
    })
    void should_classify_questions(String comment) {
        assertEquals(CommentIntent.QUESTION, classifier.classify(comment, false));
    }

    // ─── Approvals ───

    @ParameterizedTest
    @ValueSource(strings = {
            "@ai LGTM",
            "@ai approved",
            "@ai proceed with the PR",
            "@ai go ahead",
            "@ai ship it"
    })
    void should_classify_approvals(String comment) {
        assertEquals(CommentIntent.APPROVAL, classifier.classify(comment, false));
    }

    // ─── Irrelevant ───

    @Test
    void should_classify_comment_without_ai_mention_as_irrelevant() {
        assertEquals(CommentIntent.IRRELEVANT, classifier.classify("This looks good to me", false));
    }

    @Test
    void should_classify_null_as_irrelevant() {
        assertEquals(CommentIntent.IRRELEVANT, classifier.classify(null, false));
    }

    @Test
    void should_classify_blank_as_irrelevant() {
        assertEquals(CommentIntent.IRRELEVANT, classifier.classify("  ", false));
    }

    // ─── Self-Loop Prevention ───

    @Test
    void should_classify_bot_comment_as_irrelevant() {
        assertEquals(CommentIntent.IRRELEVANT, classifier.classify("@ai fix this", true));
    }

    // ─── Mention Stripping ───

    @Test
    void should_strip_mention_prefix() {
        assertEquals("fix the NPE in UserService", classifier.stripMention("@ai fix the NPE in UserService"));
    }

    @Test
    void should_handle_null_in_strip_mention() {
        assertEquals("", classifier.stripMention(null));
    }

    @Test
    void should_return_original_when_no_mention() {
        assertEquals("no mention here", classifier.stripMention("no mention here"));
    }
}
