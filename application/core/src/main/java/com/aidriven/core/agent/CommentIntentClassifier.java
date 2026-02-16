package com.aidriven.core.agent;

import com.aidriven.core.agent.model.CommentIntent;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based classifier for Jira comment intents.
 * Fast and deterministic — no LLM call needed for routing.
 */
@Slf4j
public class CommentIntentClassifier {

    // Matches literal "@ai" at any position in the text (not just start-of-string)
    private static final Pattern AI_MENTION_PATTERN = Pattern.compile(
            "@ai\\b", Pattern.CASE_INSENSITIVE);

    // Matches Jira Cloud ADF mention format: [~accountId:xxx]
    private static final Pattern JIRA_MENTION_PATTERN = Pattern.compile(
            "\\[~accountId:[^\\]]+\\]");

    private static final Set<String> APPROVAL_KEYWORDS = Set.of(
            "lgtm", "approved", "approve", "proceed", "go ahead", "ship it", "merge it");

    private static final Set<String> FEEDBACK_KEYWORDS = Set.of(
            "doesn't work", "doesn't handle", "should also", "please fix",
            "incorrect", "wrong", "missing", "instead of", "change this",
            "update this", "revert", "not what i", "try again");

    /**
     * Classifies a Jira comment's intent.
     *
     * @param commentBody Raw comment text from Jira
     * @param authorIsBot Whether the comment was posted by the bot account
     * @return The classified intent
     */
    public CommentIntent classify(String commentBody, boolean authorIsBot) {
        if (commentBody == null || commentBody.isBlank()) {
            return CommentIntent.IRRELEVANT;
        }
        // Self-loop prevention: never respond to our own comments
        if (authorIsBot) {
            log.debug("Ignoring bot-authored comment");
            return CommentIntent.IRRELEVANT;
        }

        String trimmed = commentBody.strip();

        // Must contain @ai mention (literal or via Jira ADF accountId format)
        boolean hasLiteralMention = AI_MENTION_PATTERN.matcher(trimmed).find();
        boolean hasJiraMention = JIRA_MENTION_PATTERN.matcher(trimmed).find();

        if (!hasLiteralMention && !hasJiraMention) {
            return CommentIntent.IRRELEVANT;
        }

        String body = stripMention(trimmed).strip().toLowerCase();

        // Check for approval patterns
        for (String keyword : APPROVAL_KEYWORDS) {
            if (body.contains(keyword)) {
                return CommentIntent.APPROVAL;
            }
        }

        // Check for feedback patterns (criticism or correction of AI's previous work)
        for (String keyword : FEEDBACK_KEYWORDS) {
            if (body.contains(keyword)) {
                return CommentIntent.HUMAN_FEEDBACK;
            }
        }

        // Check if it's a question
        if (body.endsWith("?") || body.startsWith("why") || body.startsWith("how")
                || body.startsWith("what") || body.startsWith("when") || body.startsWith("where")
                || body.startsWith("can you explain")) {
            return CommentIntent.QUESTION;
        }

        // Default: treat as a command
        return CommentIntent.AI_COMMAND;
    }

    /** Strips the @ai mention and Jira accountId mentions from a comment body. */
    public String stripMention(String commentBody) {
        if (commentBody == null)
            return "";
        String stripped = commentBody.strip();
        stripped = JIRA_MENTION_PATTERN.matcher(stripped).replaceAll("").strip();
        stripped = AI_MENTION_PATTERN.matcher(stripped).replaceFirst("").strip();
        return stripped;
    }
}
