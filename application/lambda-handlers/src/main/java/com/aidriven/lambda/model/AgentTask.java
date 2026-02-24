package com.aidriven.lambda.model;

import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing an agent task sent over SQS to the Processor Handler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentTask {
    private String ticketKey;
    private String commentBody;
    private String rawCommentBody;
    private String commentAuthor;
    private String commentAuthorAccountId;
    private String ackCommentId;
    private String platform;
    private OperationContext context;
    private String correlationId;

    // Parent comment excerpt for reply formatting (Jira)
    private String parentCommentExcerpt;
    private String parentCommentAuthorAccountId;

    // GitHub specific
    private String repoOwner;
    private String repoSlug;
    private String prNumber;
    private String githubCommentId;
    private String githubCommentType;
    private String diffHunk;
    private String filePath;
    private String commitId;
}
