package com.aidriven.core.agent;

import com.aidriven.core.agent.model.CommentIntent;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hybrid classifier for comment intents.
 *
 * <p>Phase 1-2: Rule-based fast path (deterministic, zero latency).
 * Phase 3+: Optional LLM fallback for ambiguous cases (behind feature flag).
 *
 * <p>The @mention keyword is configurable via {@link #CommentIntentClassifier(String)}
 * so teams can use @ai, @bot, @assistant, or any other keyword without code changes.
 */
@Slf4j
public class CommentIntentClassifier {

    // Matches Jira Cloud ADF mention format: [~accountId:xxx]
    // Note: This is used for stripping mentions, not for detecting bot mentions
    private static final Pattern JIRA_MENTION_PATTERN = Pattern.compile(
            "\\[~accountId:[^\\]]+\\]");

    // Bot signature patterns - if comment contains these, it's from the bot
    // Used for self-loop prevention when author detection fails
    private static final Set<String> BOT_SIGNATURE_PATTERNS = Set.of(
            // Acknowledgment patterns
            "🤖 processing your request",
            "working on it — this comment will be updated",
            // Response footer patterns
            "_🤖 ai agent",
            "| tools:",
            "| tokens:",
            // Error patterns
            "🤖 ❌ *error processing request*",
            // Quote patterns from formatAsReply (might trigger on bot quotes)
            "{quote}🤖",
            "{quote}\\{quote}"
    );

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

    /** Pattern built from the configured mention keyword. */
    private final Pattern mentionPattern;

    private final AiClient aiClient; // nullable — only used if LLM fallback is enabled
    private final boolean useLlmFallback;

    /** Default constructor: rule-based only, responds to @ai. */
    public CommentIntentClassifier() {
        this("ai", null, false);
    }

    /**
     * Constructor with a configurable mention keyword.
     *
     * @param mentionKeyword keyword without the @-sign (e.g. "ai", "bot", "assistant")
     */
    public CommentIntentClassifier(String mentionKeyword) {
        this(mentionKeyword, null, false);
    }

    /**
     * Full constructor.
     *
     * @param aiClient       AI client for LLM classification fallback (nullable)
     * @param useLlmFallback Whether to use Claude for ambiguous cases
     */
    public CommentIntentClassifier(AiClient aiClient, boolean useLlmFallback) {
        this("ai", aiClient, useLlmFallback);
    }

    /**
     * Full constructor with configurable keyword and optional LLM fallback.
     *
     * @param mentionKeyword keyword without the @-sign
     * @param aiClient       AI client for LLM classification fallback (nullable)
     * @param useLlmFallback Whether to use Claude for ambiguous cases
     */
    public CommentIntentClassifier(String mentionKeyword, AiClient aiClient, boolean useLlmFallback) {
        String kw = (mentionKeyword != null && !mentionKeyword.isBlank())
                ? Pattern.quote(mentionKeyword.strip().toLowerCase())
                : "ai";
        this.mentionPattern = Pattern.compile("@" + kw + "\\b", Pattern.CASE_INSENSITIVE);
        this.aiClient = aiClient;
        this.useLlmFallback = useLlmFallback && aiClient != null;
    }

    /**
     * Classifies a comment's intent.
     *
     * @param commentBody  Raw comment text from Jira or GitHub
     * @param authorIsBot  Whether the comment was posted by the bot account
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
        String lowerBody = trimmed.toLowerCase();

        // Self-loop prevention: check for bot signature patterns in comment body
        // This catches cases where authorIsBot detection fails
        for (String signature : BOT_SIGNATURE_PATTERNS) {
            if (lowerBody.contains(signature)) {
                log.debug("Ignoring comment with bot signature: {}", signature);
                return CommentIntent.IRRELEVANT;
            }
        }

        // Only respond to LITERAL @keyword mentions (e.g., @ai)
        // DO NOT respond to generic Jira ADF mentions [~accountId:xxx]
        // because those are user-to-user mentions, not bot commands
        boolean hasLiteralMention = mentionPattern.matcher(trimmed).find();

        if (!hasLiteralMention) {
            return CommentIntent.IRRELEVANT;
        }

        String body = stripMention(trimmed).strip().toLowerCase();

        // 1. Check for rejection patterns (before approval to avoid false positives)
        for (String keyword : REJECTION_KEYWORDS) {
            if (body.contains(keyword)) {
                log.info("Classified as APPROVAL (rejection) via keyword: {}", keyword);
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

        // 3. Check for feedback patterns
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

    /** Strips the @mention keyword and Jira accountId mentions from a comment body. */
    public String stripMention(String commentBody) {
        if (commentBody == null) return "";
        String stripped = commentBody.strip();
        stripped = JIRA_MENTION_PATTERN.matcher(stripped).replaceAll("").strip();
        stripped = mentionPattern.matcher(stripped).replaceFirst("").strip();
        return stripped;
    }
}
