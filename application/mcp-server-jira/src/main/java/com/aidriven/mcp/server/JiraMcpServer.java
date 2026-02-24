package com.aidriven.mcp.server;

import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.spi.model.OperationContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standalone MCP server for Jira operations.
 *
 * <p>Exposes core Jira functionality as MCP tools, consumed by
 * {@code McpBridgeToolProvider} running inside the agent Lambda.</p>
 *
 * <h2>Tools exposed</h2>
 * <ul>
 *   <li>{@code get_ticket} — fetch detailed information about a Jira ticket</li>
 *   <li>{@code add_comment} — post a comment to a Jira ticket</li>
 *   <li>{@code get_transitions} — list available workflow transitions for a ticket</li>
 *   <li>{@code transition_ticket} — move a ticket to a new status by name</li>
 *   <li>{@code update_labels} — add or remove labels on a ticket</li>
 * </ul>
 *
 * <h2>Required environment variables</h2>
 * <ul>
 *   <li>{@code JIRA_BASE_URL} — Jira Cloud base URL (e.g. https://myorg.atlassian.net)</li>
 *   <li>{@code JIRA_EMAIL} — service account email</li>
 *   <li>{@code JIRA_API_TOKEN} — Atlassian API token</li>
 * </ul>
 */
@Slf4j
public class JiraMcpServer {

    public static void main(String[] args) {
        log.info("Starting Jira MCP Server...");

        String baseUrl   = System.getenv("JIRA_BASE_URL");
        String email     = System.getenv("JIRA_EMAIL");
        String apiToken  = System.getenv("JIRA_API_TOKEN");

        if (baseUrl == null || email == null || apiToken == null) {
            log.error("Missing required environment variables: JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN");
            System.exit(1);
        }

        JiraClient jiraClient = new JiraClient(baseUrl, email, apiToken);

        OperationContext defaultContext = OperationContext.builder()
                .tenantId("default")
                .requestId("mcp-default")
                .build();

        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider())
                .serverInfo(new McpSchema.Implementation("jira-mcp-server", "1.0.0"))
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        // ── get_ticket ────────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "get_ticket",
                        "Fetch detailed information about a Jira ticket including summary, "
                                + "description, status, labels, and assignee.",
                        new McpSchema.JsonSchema("object",
                                Map.of("ticket_key",
                                        Map.of("type", "string", "description",
                                               "The Jira ticket key (e.g. PROJ-123)")),
                                List.of("ticket_key"), null, null, null)),
                (exchange, arguments) -> {
                    String key = (String) arguments.get("ticket_key");
                    try {
                        Map<String, Object> details = jiraClient.getTicketDetails(defaultContext, key);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(details.toString())), false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── add_comment ───────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "add_comment",
                        "Post a comment to a Jira ticket.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "ticket_key", Map.of("type", "string", "description",
                                                             "The Jira ticket key"),
                                        "comment",    Map.of("type", "string", "description",
                                                             "The comment text to add")),
                                List.of("ticket_key", "comment"), null, null, null)),
                (exchange, arguments) -> {
                    String key     = (String) arguments.get("ticket_key");
                    String comment = (String) arguments.get("comment");
                    try {
                        jiraClient.postComment(defaultContext, key, comment);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Comment added successfully")), false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── get_transitions ───────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "get_transitions",
                        "List available workflow transitions for a Jira ticket. "
                                + "Use this before calling transition_ticket to discover valid status names.",
                        new McpSchema.JsonSchema("object",
                                Map.of("ticket_key",
                                        Map.of("type", "string", "description",
                                               "The Jira ticket key (e.g. PROJ-123)")),
                                List.of("ticket_key"), null, null, null)),
                (exchange, arguments) -> {
                    String key = (String) arguments.get("ticket_key");
                    try {
                        List<IssueTrackerClient.Transition> transitions =
                                jiraClient.getTransitions(defaultContext, key);
                        if (transitions.isEmpty()) {
                            return new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent("No transitions available")), false);
                        }
                        String result = transitions.stream()
                                .map(t -> "id=" + t.id() + "  →  " + t.toStatus())
                                .collect(Collectors.joining("\n"));
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(
                                        "Available transitions for " + key + ":\n" + result)),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── transition_ticket ─────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "transition_ticket",
                        "Move a Jira ticket to a new status by status name (e.g. 'In Progress', 'Done'). "
                                + "Call get_transitions first to see available status names.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "ticket_key",  Map.of("type", "string", "description",
                                                              "The Jira ticket key (e.g. PROJ-123)"),
                                        "status_name", Map.of("type", "string", "description",
                                                              "Target status name exactly as returned by "
                                                                      + "get_transitions (e.g. 'In Progress')")),
                                List.of("ticket_key", "status_name"), null, null, null)),
                (exchange, arguments) -> {
                    String key        = (String) arguments.get("ticket_key");
                    String statusName = (String) arguments.get("status_name");
                    try {
                        jiraClient.updateStatus(defaultContext, key, statusName);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(
                                        "Ticket " + key + " transitioned to '" + statusName + "' successfully")),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        // ── update_labels ─────────────────────────────────────────────────────

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "update_labels",
                        "Add or remove labels on a Jira ticket. "
                                + "Pass comma-separated label names; empty string means no change.",
                        new McpSchema.JsonSchema("object",
                                Map.of(
                                        "ticket_key",     Map.of("type", "string", "description",
                                                                 "The Jira ticket key (e.g. PROJ-123)"),
                                        "labels_to_add",  Map.of("type", "string", "description",
                                                                 "Comma-separated labels to add "
                                                                         + "(e.g. 'bug,needs-review')"),
                                        "labels_to_remove", Map.of("type", "string", "description",
                                                                   "Comma-separated labels to remove "
                                                                           + "(e.g. 'in-progress')")),
                                List.of("ticket_key"), null, null, null)),
                (exchange, arguments) -> {
                    String key             = (String) arguments.get("ticket_key");
                    String addRaw          = arguments.containsKey("labels_to_add")
                            ? (String) arguments.get("labels_to_add") : "";
                    String removeRaw       = arguments.containsKey("labels_to_remove")
                            ? (String) arguments.get("labels_to_remove") : "";
                    List<String> toAdd     = parseLabelList(addRaw);
                    List<String> toRemove  = parseLabelList(removeRaw);
                    try {
                        jiraClient.updateLabels(defaultContext, key, toAdd, toRemove);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(
                                        "Labels updated on " + key
                                                + (toAdd.isEmpty()    ? "" : ". Added: "   + toAdd)
                                                + (toRemove.isEmpty() ? "" : ". Removed: " + toRemove))),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
                    }
                }));

        log.info("Jira MCP Server is running with {} tools — "
                + "waiting for JSON-RPC messages on stdin...", 5);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<String> parseLabelList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String label : raw.split(",")) {
            String trimmed = label.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
