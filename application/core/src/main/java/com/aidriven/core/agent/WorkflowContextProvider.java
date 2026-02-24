package com.aidriven.core.agent;

import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

/**
 * Provides workflow context from prior automated work (e.g., ai-generate PRs)
 * for injection into the ai-agent's system prompt.
 *
 * <p>This enables context sharing between independent workflows:
 * - ai-generate creates PRs via Step Functions
 * - ai-agent handles @ai conversations via SQS
 * - WorkflowContextProvider bridges the gap by retrieving PR metadata
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowContextProvider {

    private final TicketStateRepository ticketStateRepository;

    /**
     * Workflow context from prior automated work.
     *
     * @param prUrl       The URL of the created pull request
     * @param branchName  The branch name for the PR
     * @param status      The current processing status
     * @param lastUpdated When the state was last updated
     */
    public record WorkflowContext(
            String prUrl,
            String branchName,
            String status,
            Instant lastUpdated
    ) {
        /**
         * Formats the context for inclusion in a system prompt.
         */
        public String toPromptSection() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Prior Automated Work\n");
            sb.append("A Pull Request was automatically created for this ticket:\n");
            sb.append("- **PR URL:** ").append(prUrl).append("\n");
            if (branchName != null && !branchName.isBlank()) {
                sb.append("- **Branch:** ").append(branchName).append("\n");
            }
            sb.append("- **Status:** ").append(status).append("\n");
            sb.append("\nYou can reference this PR and use tools to check its current state.\n\n");
            return sb.toString();
        }
    }

    /**
     * Retrieves workflow context for a ticket if a PR was previously created.
     *
     * @param tenantId The tenant identifier
     * @param ticketId The ticket ID (numeric ID, not the key)
     * @return Optional containing workflow context if PR exists, empty otherwise
     */
    public Optional<WorkflowContext> getContext(String tenantId, String ticketId) {
        try {
            return ticketStateRepository.getLatestState(tenantId, ticketId)
                    .filter(state -> state.getPrUrl() != null && !state.getPrUrl().isBlank())
                    .map(this::toWorkflowContext);
        } catch (Exception e) {
            log.warn("Failed to retrieve workflow context for tenant={} ticket={}: {}",
                    tenantId, ticketId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieves workflow context by ticket key.
     * Performs a scan/query to find the ticket state by key.
     *
     * @param tenantId  The tenant identifier
     * @param ticketKey The ticket key (e.g., PROJ-123)
     * @return Optional containing workflow context if PR exists, empty otherwise
     */
    public Optional<WorkflowContext> getContextByKey(String tenantId, String ticketKey) {
        try {
            // The repository uses ticketId in the PK, but we can search history
            // For now, we'll extract ticketId from the key's numeric suffix as a heuristic
            // In production, you'd want a GSI on ticketKey
            return ticketStateRepository.getLatestState(tenantId, ticketKey)
                    .filter(state -> state.getPrUrl() != null && !state.getPrUrl().isBlank())
                    .map(this::toWorkflowContext);
        } catch (Exception e) {
            log.warn("Failed to retrieve workflow context by key for tenant={} ticket={}: {}",
                    tenantId, ticketKey, e.getMessage());
            return Optional.empty();
        }
    }

    private WorkflowContext toWorkflowContext(TicketState state) {
        return new WorkflowContext(
                state.getPrUrl(),
                state.getBranchName(),
                state.getStatus(),
                state.getUpdatedAt()
        );
    }
}
