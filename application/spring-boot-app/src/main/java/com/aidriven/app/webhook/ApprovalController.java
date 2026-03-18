package com.aidriven.app.webhook;

import com.aidriven.app.config.AgentConfig.SwarmOrchestratorFactory;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.ApprovalStore.PendingApproval;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.source.SourceControlClientResolver;
import com.aidriven.jira.JiraClient;
import com.aidriven.spi.model.OperationContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller replacing the approval handling logic from ApprovalHandler.
 * Processes approval or rejection of gated tool actions via a REST endpoint.
 */
@Slf4j
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalStore approvalStore;
    private final JiraClient jiraClient;
    private final JiraCommentFormatter formatter;
    private final SwarmOrchestratorFactory orchestratorFactory;
    private final SourceControlClientResolver clientResolver;

    public ApprovalController(
            ApprovalStore approvalStore,
            JiraClient jiraClient,
            JiraCommentFormatter formatter,
            SwarmOrchestratorFactory orchestratorFactory,
            SourceControlClientResolver clientResolver) {
        this.approvalStore = approvalStore;
        this.jiraClient = jiraClient;
        this.formatter = formatter;
        this.orchestratorFactory = orchestratorFactory;
        this.clientResolver = clientResolver;
    }

    /**
     * POST /api/approvals/process - Processes an approval or rejection for a ticket.
     * Expects a JSON body with ticketKey, commentBody, platform, repoOwner, repoSlug.
     */
    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processApproval(@RequestBody Map<String, Object> input) {
        String ticketKey = (String) input.get("ticketKey");
        String commentBody = (String) input.get("commentBody");
        String platform = (String) input.getOrDefault("platform", "JIRA");
        String repoOwner = (String) input.get("repoOwner");
        String repoSlug = (String) input.get("repoSlug");

        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        MDC.put("ticketKey", ticketKey);

        try {
            if (ticketKey == null || ticketKey.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "ticketKey is required"));
            }

            Optional<PendingApproval> pendingOpt = approvalStore.getLatestPending(ticketKey);

            if (pendingOpt.isEmpty()) {
                String msg = "No pending approval found for this ticket. " +
                        "There may be no action awaiting approval, or it may have expired.";
                return ResponseEntity.ok(Map.of(
                        "status", "no_pending",
                        "message", msg));
            }

            PendingApproval pending = pendingOpt.get();
            String lower = commentBody != null ? commentBody.toLowerCase().trim() : "";

            if (lower.contains("reject") || lower.contains("cancel") || lower.contains("deny")) {
                approvalStore.consumeApproval(ticketKey, pending.sk());
                log.info("Approval rejected for ticket={} tool={}", ticketKey, pending.toolName());
                return ResponseEntity.ok(Map.of(
                        "status", "rejected",
                        "message", "Rejected: " + pending.approvalPrompt()));
            }

            // Execute the approved action
            log.info("Executing approved action for ticket={}: tool={}", ticketKey, pending.toolName());

            SourceControlClient scClient = clientResolver.resolve(platform, repoOwner, repoSlug);
            GuardedToolRegistry guardedRegistry = orchestratorFactory.buildGuardedRegistry(scClient);

            OperationContext context = OperationContext.builder()
                    .tenantId((String) input.getOrDefault("tenantId", "default"))
                    .userId((String) input.getOrDefault("userId", "system"))
                    .build();

            ToolResult result = guardedRegistry.executeApproved(context, ticketKey, pending);

            String status = result.isError() ? "error" : "approved";
            String message = result.isError()
                    ? "Approved action failed: " + result.content()
                    : "Approved and executed: " + pending.toolName();

            return ResponseEntity.ok(Map.of(
                    "status", status,
                    "message", message,
                    "toolName", pending.toolName()));

        } catch (Exception e) {
            log.error("Error processing approval for ticket={}", ticketKey, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Internal error processing approval"));
        } finally {
            MDC.remove("correlationId");
            MDC.remove("ticketKey");
        }
    }
}
