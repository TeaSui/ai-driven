package com.aidriven.core.agent.guardrail;

import com.aidriven.core.agent.tool.*;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.notification.ApprovalNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * Decorator around {@link ToolRegistry} that enforces risk-based guardrails.
 *
 * <p>
 * Before executing a tool call:
 * <ol>
 * <li>Assess risk level via {@link ToolRiskRegistry}</li>
 * <li>LOW/MEDIUM: execute immediately (delegate to wrapped registry)</li>
 * <li>HIGH: store pending approval in {@link ApprovalStore}, return approval
 * prompt</li>
 * </ol>
 *
 * <p>
 * When an APPROVAL intent arrives, the orchestrator calls
 * {@link #executeApproved(String, ApprovalStore.PendingApproval)} to execute
 * the gated action.
 */
@Slf4j
public class GuardedToolRegistry {

    private final ToolRegistry delegate;
    private final ToolRiskRegistry riskRegistry;
    private final ApprovalStore approvalStore;
    private final ApprovalNotifier approvalNotifier;
    private final boolean fallbackToJira;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public GuardedToolRegistry(ToolRegistry delegate, ToolRiskRegistry riskRegistry,
            ApprovalStore approvalStore, ApprovalNotifier approvalNotifier,
            boolean fallbackToJira, boolean enabled) {
        this.delegate = delegate;
        this.riskRegistry = riskRegistry;
        this.approvalStore = approvalStore;
        this.approvalNotifier = approvalNotifier;
        this.fallbackToJira = fallbackToJira;
        this.objectMapper = new ObjectMapper();
        this.enabled = enabled;
    }

    /**
     * Get available tools (delegates to underlying registry).
     */
    public List<Tool> getAvailableTools(TicketInfo ticket) {
        return delegate.getAvailableTools(ticket);
    }

    /**
     * Execute a tool call with guardrail enforcement.
     *
     * @param call        The tool call from Claude
     * @param ticketKey   Jira ticket key (for approval storage)
     * @param requestedBy Who initiated the conversation
     * @return Tool result (either execution result or approval prompt)
     */
    public ToolResult execute(OperationContext context, ToolCall call, String ticketKey, String requestedBy) {
        if (!enabled) {
            return delegate.execute(context, call);
        }

        ActionPolicy policy = riskRegistry.buildPolicy(call);
        log.info("Tool {} assessed as {} risk (approval required: {})",
                call.name(), policy.level(), policy.requiresApproval());

        if (!policy.requiresApproval()) {
            return delegate.execute(context, call);
        }

        // HIGH risk — store approval and return prompt
        return storeAndPrompt(call, ticketKey, requestedBy, policy);
    }

    /**
     * Execute a previously approved tool call.
     * Called when an APPROVAL intent is processed.
     */
    public ToolResult executeApproved(OperationContext context, String ticketKey,
            ApprovalStore.PendingApproval approval) {
        log.info("Executing approved action: ticket={} tool={}", ticketKey, approval.toolName());

        try {
            // Reconstruct the ToolCall from stored data
            ToolCall call = new ToolCall(
                    approval.toolCallId(),
                    approval.toolName(),
                    objectMapper.readTree(approval.toolInputJson()));

            // Execute via delegate (bypass guardrails — already approved)
            ToolResult result = delegate.execute(context, call);

            // Mark approval as consumed
            approvalStore.consumeApproval(ticketKey, approval.sk());

            return result;
        } catch (Exception e) {
            log.error("Failed to execute approved action: {}", e.getMessage(), e);
            return ToolResult.error(approval.toolCallId(),
                    "Failed to execute approved action: " + e.getMessage());
        }
    }

    /**
     * Get the underlying registry for non-guarded operations
     * (e.g., tool definition listing).
     */
    public ToolRegistry getDelegate() {
        return delegate;
    }

    public Set<String> getRegisteredNamespaces() {
        return delegate.getRegisteredNamespaces();
    }

    private ToolResult storeAndPrompt(ToolCall call, String ticketKey, String requestedBy,
            ActionPolicy policy) {
        try {
            String inputJson = objectMapper.writeValueAsString(call.input());

            approvalStore.storePendingApproval(
                    ticketKey,
                    call.id(),
                    call.name(),
                    inputJson,
                    policy.level(),
                    policy.approvalPrompt(),
                    requestedBy);

            try {
                long timeoutSeconds = 24 * 3600;
                ApprovalNotifier.PendingApprovalContext pendingCtx = new ApprovalNotifier.PendingApprovalContext(
                        ticketKey,
                        call.name(),
                        policy.approvalPrompt(),
                        "AI Agent",
                        "Risk Level: " + policy.level(),
                        timeoutSeconds);
                approvalNotifier.notifyPending(pendingCtx);
            } catch (Exception notifyEx) {
                log.error("Failed to notify external system (Slack) for ticket={}", ticketKey, notifyEx);
                if (!fallbackToJira) {
                    throw new RuntimeException("Notification failed and fallback is disabled", notifyEx);
                }
            }

            String prompt = String.format(
                    "⚠\uFE0F *Approval required* — %s\n\n" +
                            "This action is classified as *%s risk*.\n" +
                            "Reply with `@ai approve` to proceed, or `@ai reject` to cancel.",
                    policy.approvalPrompt(), policy.level());

            // Return as a non-error tool result so Claude can include it in its response
            return ToolResult.success(call.id(), prompt);

        } catch (Exception e) {
            log.error("Failed to store approval request: {}", e.getMessage(), e);
            return ToolResult.error(call.id(),
                    "Failed to create approval request: " + e.getMessage());
        }
    }
}
