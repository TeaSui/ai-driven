package com.aidriven.core.agent.model;

/**
 * Classifies the intent behind a Jira comment directed at the agent.
 */
public enum CommentIntent {
    /** Direct command to the AI agent (e.g., "@ai fix the NPE in UserService"). */
    AI_COMMAND,

    /**
     * Human leaving feedback on AI's work (e.g., "The generated code doesn't handle
     * nulls").
     */
    HUMAN_FEEDBACK,

    /**
     * Question requiring investigation (e.g., "@ai why is this endpoint slow?").
     */
    QUESTION,

    /** Approval or sign-off (e.g., "@ai LGTM, proceed with PR"). */
    APPROVAL,

    /**
     * Internal intent for reviewing code changes.
     */
    REVIEW,

    /**
     * Internal intent for testing code changes.
     */
    TEST,

    /** Comment not directed at the agent. */
    IRRELEVANT
}
