package com.aidriven.core.agent;

import com.aidriven.core.agent.model.CommentIntent;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hybrid classifier for Jira comment intents.
 * <p>
 * Phase 1-2: Rule-based fast path (deterministic, zero latency).
 * Phase 3+: Optional LLM fallback for ambiguous cases (behind feature flag).
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
            "lgtm", "approved", "approve", "proceed", "go ahead", "ship it",
            "merge it", "looks good", "accept", "confirm", "yes do it", "yes, do it");

    private static final Set<String> REJECTION_KEYWORDS = Set.of(
            "reject", "cancel", "don't do it", "abort", "stop", "no don't", "no, don't");

    private static final Set<String> FEEDBACK_KEYWORDS = Set.of(
            "doesn't work", "doesn't handle", "should also", "please fix",
            "incorrect", "wrong", "missing", "instead of", "change this",
            "update this", "revert", "not what i", "try again",
            "use optional", "use a different", "better approach", "refactor",
            "rename", "remove the", "add a test", "add tests", "add error handling",
            "handle the case", "what about", "you forgot", "also need");

    private static final Set<String> QUESTION_STARTERS = Set.of(
            "why", "how", "what", "when", "where", "which", "who",
            "can you explain", "could you explain", "is there", "are there",
            "do you", "does this", "will this");

    private final AiClient aiClient; // nullable — only used if LLM fallback is enabled
    private final boolean useLlmFallback;

    /** Default constructor: rule-based only, no LLM fallback. */
    public CommentIntentClassifier() {
        this(null, false);
    }

    /**
     * @param aiClient       AI client for LLM classification fallback (nullable)
     * @param useLlmFallback Whether to use Claude for ambiguous cases
     */
    public CommentIntentClassifier(AiClient aiClient, boolean useLlmFallback) {
        this.aiClient = aiClient;
        this.useLlmFallback = useLlmFallback && aiClient != null;
    }

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

        // 1. Check for rejection patterns (before approval to avoid false positives)
        for (String keyword : REJECTION_KEYWORDS) {
            if (body.contains(keyword)) {
                log.info("Classified as APPROVAL (rejection) via keyword: {}", keyword);
                // Rejection is handled through the approval flow (orchestrator checks content)
                return CommentIntent.APPROVAL;
            }
        }

        // 2. Check for approval patterns
        for (String keyword : APPROVAL_KEYWORDS) {
            if (body.contains(keyword)) {
                log.info("Classified as APPROVAL via keyword: {}", keyword);
                return CommentIntent.APPROVAL;
            }
        }

        // 3. Check for feedback patterns (criticism or correction of AI's previous work)
        for (String keyword : FEEDBACK_KEYWORDS) {
            if (body.contains(keyword)) {
                log.info("Classified as HUMAN_FEEDBACK via keyword: {}", keyword);
                return CommentIntent.HUMAN_FEEDBACK;
            }
        }

        // 4. Check if it's a question
        if (body.endsWith("?")) {
            return CommentIntent.QUESTION;
        }
        for (String starter : QUESTION_STARTERS) {
            if (body.startsWith(starter)) {
                return CommentIntent.QUESTION;
            }
        }

        // 5. Default: treat as a command
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
