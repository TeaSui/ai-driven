package com.aidriven.tool.tracker;

import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Exposes {@link IssueTrackerClient} operations as Claude tools.
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
                        "Get full details of a Jira ticket.",
                        Map.of("ticket_key", Tool.stringProp("Ticket key")),
                        "ticket_key"),

                Tool.of("issue_tracker_add_comment",
                        "Add a comment to a Jira ticket.",
                        Map.of("ticket_key", Tool.stringProp("Ticket key"), "comment", Tool.stringProp("Comment text")),
                        "ticket_key", "comment"),

                Tool.of("issue_tracker_update_status",
                        "Update ticket status.",
                        Map.of("ticket_key", Tool.stringProp("Ticket key"), "status",
                                Tool.stringProp("Target status name")),
                        "ticket_key", "status"));
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        String action = call.name().substring(namespace().length() + 1);
        JsonNode input = call.input();

        try {
            return switch (action) {
                case "get_ticket" -> getTicket(context, call.id(), input);
                case "add_comment" -> addComment(context, call.id(), input);
                case "update_status" -> updateStatus(context, call.id(), input);
                default -> ToolResult.error(call.id(), "Unknown action: " + action);
            };
        } catch (Exception e) {
            log.error("Issue tracker tool error: {} - {}", action, e.getMessage(), e);
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
    }

    private ToolResult getTicket(OperationContext context, String toolUseId, JsonNode input) throws Exception {
        String ticketKey = input.get("ticket_key").asText();
        TicketInfo ticket = client.getTicket(context, ticketKey);

        StringBuilder sb = new StringBuilder();
        sb.append("Ticket: ").append(ticket.getTicketKey()).append("\n");
        sb.append("Title: ").append(ticket.getSummary()).append("\n");
        sb.append("Status: ").append(ticket.getStatus()).append("\n");
        if (ticket.getDescription() != null) {
            sb.append("Description:\n").append(ticket.getDescription()).append("\n");
        }
        return ToolResult.success(toolUseId, sb.toString());
    }

    private ToolResult addComment(OperationContext context, String toolUseId, JsonNode input) throws Exception {
        String ticketKey = input.get("ticket_key").asText();
        String comment = input.get("comment").asText();
        client.addComment(context, ticketKey, comment);
        return ToolResult.success(toolUseId, "Comment added");
    }

    private ToolResult updateStatus(OperationContext context, String toolUseId, JsonNode input) throws Exception {
        String ticketKey = input.get("ticket_key").asText();
        String status = input.get("status").asText();
        client.updateStatus(context, ticketKey, status);
        return ToolResult.success(toolUseId, "Status updated");
    }
}
