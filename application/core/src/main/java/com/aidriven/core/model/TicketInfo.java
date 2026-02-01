package com.aidriven.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a Jira ticket with all relevant metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketInfo {
    
    private String ticketId;
    private String ticketKey;
    private String projectKey;
    private String summary;
    private String description;
    private List<String> labels;
    private String status;
    private String assignee;
    private String reporter;
    private String priority;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> customFields;
    
    private static final List<String> DRY_RUN_LABELS = List.of("ai-test", "dry-run", "test-mode");

    /**
     * Checks if this ticket should be processed in dry-run mode.
     * Dry-run mode skips Claude API calls and PR creation, useful for testing.
     */
    public boolean isDryRun() {
        if (labels == null || labels.isEmpty()) {
            return false;
        }
        return labels.stream()
                .map(String::toLowerCase)
                .anyMatch(label -> DRY_RUN_LABELS.stream().anyMatch(label::contains));
    }

    /**
     * Determines the agent type based on ticket labels and description.
     */
    public AgentType determineAgentType() {
        // 1. Check labels first (explicit)
        if (labels != null && !labels.isEmpty()) {
            for (String label : labels) {
                String lowerLabel = label.toLowerCase();
                if (lowerLabel.contains("frontend")) return AgentType.FRONTEND;
                if (lowerLabel.contains("security")) return AgentType.SECURITY;
                if (lowerLabel.contains("backend")) return AgentType.BACKEND;
            }
        }
        
        // 2. Check description/summary as fallback
        String content = (summary + " " + description).toLowerCase();
        if (content.contains("frontend") || content.contains("react") || content.contains("css")) {
            return AgentType.FRONTEND;
        }
        if (content.contains("security") || content.contains("vulnerability") || content.contains("cve")) {
            return AgentType.SECURITY;
        }
        
        return AgentType.BACKEND; // Default
    }
}
