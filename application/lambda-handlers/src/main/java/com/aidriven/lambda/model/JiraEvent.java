package com.aidriven.lambda.model;

import com.aidriven.spi.model.OperationContext;
import lombok.Builder;
import lombok.Value;

/**
 * Represents a webhook event originating from Jira (comment created).
 */
@Value
@Builder
public class JiraEvent implements WebhookEvent {
    String ticketKey;
    String commentBody;
    String commentAuthor;        // display name
    String commentAuthorAccountId; // immutable Jira accountId (preferred for bot detection)
    OperationContext operationContext;
    String eventIdempotencyKey;

    @Override
    public String getPlatform() {
        return "JIRA";
    }
}
