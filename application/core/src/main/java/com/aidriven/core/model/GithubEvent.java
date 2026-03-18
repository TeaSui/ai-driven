package com.aidriven.core.model;

import com.aidriven.spi.model.OperationContext;
import lombok.Builder;
import lombok.Value;

/**
 * Represents a webhook event originating from GitHub (PR or issue comment).
 */
@Value
@Builder
public class GithubEvent implements WebhookEvent {
    String ticketKey;          // PR number
    String commentBody;
    String commentAuthor;      // GitHub login (display)
    String commentAuthorAccountId; // GitHub login (same as commentAuthor for GitHub)
    OperationContext operationContext;
    String eventIdempotencyKey; // e.g. "GH-1234567"

    // GitHub-specific fields
    String repoOwner;
    String repoSlug;
    String prNumber;
    String githubCommentId;
    String githubCommentType; // "REVIEW" or "ISSUE"
    String diffHunk;
    String filePath;
    String commitId;

    @Override
    public String getPlatform() {
        return "GITHUB";
    }
}
