package com.aidriven.lambda.approval;

import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.guardrail.ApprovalStore.PendingApproval;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.lambda.model.AgentTask;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.tool.context.CodeContextToolProvider;
import com.aidriven.tool.context.ContextService;
import com.aidriven.tool.tracker.IssueTrackerToolProvider;
import com.aidriven.tool.source.SourceControlToolProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Handles the logic for processing approvals or rejections of gated tool
 * actions.
 */
@Slf4j
@RequiredArgsConstructor
public class ApprovalHandler {

    private final ServiceFactory serviceFactory;
    private final JiraClient jiraClient;
    private final JiraCommentFormatter formatter;

    public void handle(AgentTask task, SourceControlClient scClient) throws Exception {
        String ticketKey = task.getTicketKey();
        OperationContext context = task.getContext();
        String commentBody = task.getCommentBody();

        ApprovalStore approvalStore = serviceFactory.getApprovalStore();
        Optional<PendingApproval> pendingOpt = approvalStore.getLatestPending(ticketKey);

        if (pendingOpt.isEmpty()) {
            String msg = "No pending approval found for this ticket. " +
                    "There may be no action awaiting approval, or it may have expired.";
            postResponse(task, scClient, formatter.format(msg, List.of(), 0));
            return;
        }

        PendingApproval pending = pendingOpt.get();
        String lower = commentBody.toLowerCase().trim();

        if (lower.contains("reject") || lower.contains("cancel") || lower.contains("deny")) {
            approvalStore.consumeApproval(ticketKey, pending.sk());
            String msg = "Rejected: " + pending.approvalPrompt() + "\nThe action has been cancelled.";
            postResponse(task, scClient, formatter.format(msg, List.of(), 0));
            log.info("Approval rejected for ticket={} tool={}", ticketKey, pending.toolName());
            return;
        }

        // Execute the approved action
        log.info("Executing approved action for ticket={}: tool={}", ticketKey, pending.toolName());

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new SourceControlToolProvider(scClient));
        toolRegistry.register(new IssueTrackerToolProvider(jiraClient));

        ContextService contextService = serviceFactory.createContextService(scClient);
        toolRegistry.register(new CodeContextToolProvider(contextService));

        for (McpBridgeToolProvider mcpProvider : serviceFactory.getMcpToolProviders()) {
            toolRegistry.register(mcpProvider);
        }

        GuardedToolRegistry guardedToolRegistry = serviceFactory.createGuardedToolRegistry(toolRegistry);
        ToolResult result = guardedToolRegistry.executeApproved(context, ticketKey, pending);

        String msg = result.isError()
                ? "Approved action failed: " + result.content()
                : "Approved and executed: " + pending.toolName() + "\n\n" + result.content();

        postResponse(task, scClient, formatter.format(msg, List.of(pending.toolName()), 0));
    }

    private void postResponse(AgentTask task, SourceControlClient scClient, String formattedResponse) {
        OperationContext context = task.getContext();
        String platform = task.getPlatform();
        String ticketKey = task.getTicketKey();
        String ackCommentId = task.getAckCommentId();

        if ("GITHUB".equalsIgnoreCase(platform)) {
            String prNum = task.getPrNumber() != null ? task.getPrNumber() : ticketKey;
            String commentId = task.getGithubCommentId();

            try {
                if (commentId != null && !commentId.isEmpty()) {
                    scClient.addPrCommentReply(context, prNum, commentId, formattedResponse);
                } else {
                    scClient.addPrComment(context, prNum, formattedResponse);
                }
                log.info("Posted response to GitHub PR={}", prNum);
            } catch (Exception e) {
                log.error("Failed to post response to GitHub PR={}", prNum, e);
            }
            return;
        }

        // Default to Jira
        try {
            jiraClient.editComment(context, ticketKey, ackCommentId, formattedResponse);
            log.info("Updated ack comment {} for ticket={}", ackCommentId, ticketKey);
        } catch (Exception e) {
            log.warn("Failed to edit ack comment, posting new comment for ticket={}", ticketKey);
            try {
                jiraClient.postComment(context, ticketKey, formattedResponse);
            } catch (Exception ex) {
                log.error("Failed to post fallback comment for ticket={}", ticketKey, ex);
            }
        }
    }
}
