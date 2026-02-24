package com.aidriven.lambda.parser;

import com.aidriven.lambda.model.GithubEvent;
import com.aidriven.lambda.model.JiraEvent;
import com.aidriven.lambda.model.WebhookEvent;
import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.regex.Pattern;

public class WebhookParser {

    private static final Pattern GITHUB_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{1,100}$");

    public static WebhookEvent parse(JsonNode payload) {
        if (isGitHub(payload)) {
            return parseGithubEvent(payload);
        } else {
            return parseJiraEvent(payload);
        }
    }

    private static boolean isGitHub(JsonNode payload) {
        return payload.has("repository")
                && (payload.has("pull_request") || payload.has("issue") || payload.has("review")
                        || payload.has("workflow_run"));
    }

    private static JiraEvent parseJiraEvent(JsonNode payload) {
        String ticketKey = null;
        JsonNode issue = payload.path("issue");
        if (issue.has("key")) {
            ticketKey = issue.get("key").asText();
        }

        String commentBody = null;
        JsonNode comment = payload.path("comment");
        if (comment.has("body")) {
            commentBody = comment.get("body").asText();
        }

        String commentAuthor = "unknown";
        String commentAuthorAccountId = null;
        JsonNode author = payload.path("comment").path("author");
        if (author.has("displayName")) {
            commentAuthor = author.get("displayName").asText();
        }
        if (author.has("accountId")) {
            commentAuthorAccountId = author.get("accountId").asText();
        }

        OperationContext context = extractContext(payload);

        return JiraEvent.builder()
                .ticketKey(ticketKey)
                .commentBody(commentBody)
                .commentAuthor(commentAuthor)
                .commentAuthorAccountId(commentAuthorAccountId)
                .operationContext(context)
                .build();
    }

    private static GithubEvent parseGithubEvent(JsonNode payload) {
        if (payload.has("workflow_run")) {
            return parseGithubWorkflowRunEvent(payload);
        }

        String ticketKey = null;
        JsonNode pullRequest = payload.path("pull_request");
        if (pullRequest.has("number")) {
            ticketKey = pullRequest.get("number").asText();
        } else {
            JsonNode issueNode = payload.path("issue");
            if (issueNode.has("number")) {
                ticketKey = issueNode.get("number").asText();
            }
        }

        String commentBody = null;
        JsonNode comment = payload.path("comment");
        if (comment.has("body")) {
            commentBody = comment.get("body").asText();
        } else {
            JsonNode review = payload.path("review");
            if (review.has("body")) {
                commentBody = review.get("body").asText();
            }
        }

        String commentAuthor = "unknown";
        JsonNode user = payload.path("comment").path("user");
        if (user.has("login")) {
            commentAuthor = user.get("login").asText();
        } else {
            JsonNode reviewUser = payload.path("review").path("user");
            if (reviewUser.has("login")) {
                commentAuthor = reviewUser.get("login").asText();
            }
        }

        // For GitHub, login is the immutable account identifier
        String commentAuthorAccountId = commentAuthor.equals("unknown") ? null : commentAuthor;

        OperationContext context = extractContext(payload);

        String repoOwner = payload.path("repository").path("owner").path("login").asText();
        String repoSlug = payload.path("repository").path("name").asText();

        // Validation for repoOwner and repoSlug
        if (!GITHUB_NAME_PATTERN.matcher(repoOwner).matches()) {
            throw new IllegalArgumentException("Invalid GitHub repoOwner format: " + repoOwner);
        }
        if (!GITHUB_NAME_PATTERN.matcher(repoSlug).matches()) {
            throw new IllegalArgumentException("Invalid GitHub repoSlug format: " + repoSlug);
        }

        String githubCommentId = null;
        if (comment.has("id")) {
            githubCommentId = comment.path("id").asText();
        }

        String idempotencyKey = githubCommentId != null ? "GH-" + githubCommentId : "GH-" + java.util.UUID.randomUUID();

        String commentType = null;
        if (payload.has("pull_request_review_comment")
                || "pull_request_review_comment".equals(payload.path("action").asText())) {
            commentType = "REVIEW";
        } else if (payload.has("issue") || payload.has("pull_request")) {
            commentType = "ISSUE";
        }

        return GithubEvent.builder()
                .ticketKey(ticketKey)
                .commentBody(commentBody)
                .commentAuthor(commentAuthor)
                .commentAuthorAccountId(commentAuthorAccountId)
                .operationContext(context)
                .repoOwner(repoOwner)
                .repoSlug(repoSlug)
                .prNumber(ticketKey)
                .githubCommentId(githubCommentId)
                .githubCommentType(commentType)
                .diffHunk(comment.has("diff_hunk") ? comment.path("diff_hunk").asText() : null)
                .filePath(comment.has("path") ? comment.path("path").asText() : null)
                .commitId(comment.has("commit_id") ? comment.path("commit_id").asText() : null)
                .eventIdempotencyKey(idempotencyKey)
                .build();
    }

    private static GithubEvent parseGithubWorkflowRunEvent(JsonNode payload) {
        JsonNode run = payload.path("workflow_run");
        String runId = run.path("id").asText();
        String conclusion = run.path("conclusion").asText();
        String headBranch = run.path("head_branch").asText();

        String ticketKey = null;
        java.util.regex.Matcher m = Pattern.compile("(?i)^(?:ai/)?([A-Z]+-\\d+)").matcher(headBranch);
        if (m.find()) {
            ticketKey = m.group(1).toUpperCase();
        } else if (run.has("pull_requests") && run.path("pull_requests").isArray()
                && !run.path("pull_requests").isEmpty()) {
            ticketKey = run.path("pull_requests").get(0).path("number").asText();
        } else {
            ticketKey = "UNKNOWN";
        }

        String commentBody = String.format(
                "@ai CI Build Failed on branch %s for ticket %s.\nRun ID: %s\nConclusion: %s\nAction: Use the get_ci_logs tool to fetch and analyze the test/build logs, then propose and commit a fix.",
                headBranch, ticketKey, runId, conclusion);

        String commentAuthor = payload.path("sender").path("login").asText("github-actions");
        String commentAuthorAccountId = commentAuthor;

        OperationContext context = extractContext(payload);
        String repoOwner = payload.path("repository").path("owner").path("login").asText();
        String repoSlug = payload.path("repository").path("name").asText();

        String idempotencyKey = "GH-RUN-" + runId;

        return GithubEvent.builder()
                .ticketKey(ticketKey)
                .commentBody(commentBody)
                .commentAuthor(commentAuthor)
                .commentAuthorAccountId(commentAuthorAccountId)
                .operationContext(context)
                .repoOwner(repoOwner)
                .repoSlug(repoSlug)
                .prNumber(ticketKey) // Use ticketKey as prNumber fallback
                .githubCommentId(runId)
                .githubCommentType("CI_FAILURE")
                .eventIdempotencyKey(idempotencyKey)
                .build();
    }

    private static OperationContext extractContext(JsonNode payload) {
        String tenantId = "default";
        // Jira webhooks sometimes have baseUrl
        if (payload.has("baseUrl")) {
            tenantId = payload.get("baseUrl").asText()
                    .replaceAll("https?://", "")
                    .replaceAll("\\.atlassian\\.net.*", "");
        }

        String userId = "system";
        if (payload.path("comment").path("author").has("accountId")) {
            userId = payload.path("comment").path("author").path("accountId").asText();
        } else if (payload.path("sender").has("login")) {
            userId = payload.path("sender").path("login").asText();
        }

        return OperationContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .build();
    }
}
