package com.aidriven.lambda.model;

import com.aidriven.spi.model.OperationContext;

/**
 * Base interface for structured webhook events.
 */
public sealed interface WebhookEvent permits JiraEvent, GithubEvent {

    /** Gets the primary identifier for the event (e.g., ticket key or PR number) */
    String getTicketKey();

    /** Gets the full body of the triggering comment */
    String getCommentBody();

    /** Gets the author of the comment */
    String getCommentAuthor();

    /** Gets the platform this event originated from */
    String getPlatform();

    /** Extracts multi-tenancy context from the event */
    OperationContext getOperationContext();

    /** The uniqueness token for idempotency. Usually a comment ID. */
    String getEventIdempotencyKey();

    /**
     * Returns the platform-specific immutable account identifier of the comment author.
     * For Jira: the {@code accountId} field. For GitHub: the user {@code login}.
     * Nullable — implementations return null when the payload doesn't contain this field.
     */
    String getCommentAuthorAccountId();
}
