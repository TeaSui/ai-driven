package com.aidriven.tool.tracker;

import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.ToolProvider;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Exposes {@link IssueTrackerClient} operations as Claude tools.
 *
 * <p>
 * Tools: get_ticket, add_comment, update_status
 * </p>
 */
@Slf4j
public class IssueTrackerToolProvider implements ToolProvider {

    private final IssueTrackerClient client;

    public IssueTrackerToolProvider(IssueTrackerClient client) {
        this.client = client;
    }

    @Override
    public String namespace() {
        return "issue_tracker";
    }

    @Override
    public List<Tool> toolDefinitions() {
        return List.of(
                Tool.of("issue_tracker_get_ticket",
                        "Get full details of a Jira ticket including title, description, status, and labels.",
                        Map.of("ticket_key", Tool.stringProp("Ticket key (e.g., PROJ-123)")),
                        "ticket_key"),

                Tool.of("issue_tracker_add_comment",
                        "Add a comment to a Jira ticket.",
                        Map.of(
                                "ticket_key", Tool.stringProp("Ticket key"),
                                "comment", Tool.stringProp("Comment text")),
                        "ticket_key", "comment"),

                Tool.of("issue_tracker_update_status",
                        "Update the status of a Jira ticket (e.g., 'In Progress', 'In Review', 'Done').",
                        Map.of(
                                "ticket_key", Tool.stringProp("Ticket key"),
                                "status", Tool.stringProp("Target status name")),
                        "ticket_key", "status"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String action = call.name().substring(namespace().length() + 1);
        JsonNode input = call.input();

        try {
            return switch (action) {
                case "get_ticket" -> getTicket(call.id(), input);
                case "add_comment" -> addComment(call.id(), input);
                case "update_status" -> updateStatus(call.id(), input);
                default -> ToolResult.error(call.id(), "Unknown action: " + action);
            };
        } catch (Exception e) {
            log.error("Issue tracker tool error: {} - {}", action, e.getMessage(), e);
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
    }

    private ToolResult getTicket(String toolUseId, JsonNode input) throws Exception {
        String ticketKey = input.get("ticket_key").asText();
        TicketInfo ticket = client.getTicket(ticketKey);

        StringBuilder sb = new StringBuilder();
        sb.append("Ticket: ").append(ticket.getTicketKey()).append("\n");
        sb.append("Title: ").append(ticket.getSummary()).append("\n");
        sb.append("Status: ").append(ticket.getStatus()).append("\n");
        if (ticket.getDescription() != null) {
            sb.append("Description:\n").append(ticket.getDescription()).append("\n");
        }
        if (ticket.getLabels() != null && !ticket.getLabels().isEmpty()) {
            sb.append("Labels: ").append(String.join(", ", ticket.getLabels())).append("\n");
        }

        return ToolResult.success(toolUseId, sb.toString());
    }

    private ToolResult addComment(String toolUseId, JsonNode input) throws Exception {
        String ticketKey = input.get("ticket_key").asText();
        String comment = input.get("comment").asText();
        client.addComment(ticketKey, comment);
        return ToolResult.success(toolUseId, "Comment added to " + ticketKey);
    }

    private ToolResult updateStatus(String toolUseId, JsonNode input) throws Exception {
        String ticketKey = input.get("ticket_key").asText();
        String status = input.get("status").asText();
        client.updateStatus(ticketKey, status);
        return ToolResult.success(toolUseId, "Status of " + ticketKey + " updated to '" + status + "'");
    }
}
