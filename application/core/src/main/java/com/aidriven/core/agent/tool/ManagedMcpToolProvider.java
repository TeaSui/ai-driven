package com.aidriven.core.agent.tool;

import com.aidriven.spi.provider.ProviderRegistry;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.SourceControlProvider;
import com.aidriven.spi.provider.IssueTrackerProvider;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unified tool provider that wraps internal SPI implementations.
 * Exposes tools for source control and issue tracking without external
 * processes.
 */
@Slf4j
@RequiredArgsConstructor
public class ManagedMcpToolProvider implements ToolProvider {

    private final ProviderRegistry registry;

    @Override
    public String namespace() {
        return "managed"; // Or we could use multiple namespaces if we want to split them
    }

    @Override
    public List<Tool> toolDefinitions() {
        List<Tool> tools = new ArrayList<>();

        // Issue Tracker Tools
        tools.add(Tool.of("managed_get_ticket",
                "Get Jira ticket details (in-process).",
                Map.of("ticket_key", Tool.stringProp("Ticket key")), "ticket_key"));

        tools.add(Tool.of("managed_add_comment",
                "Add a comment to a ticket.",
                Map.of("ticket_key", Tool.stringProp("Ticket key"), "comment", Tool.stringProp("Body")),
                "ticket_key", "comment"));

        // Source Control Tools
        tools.add(Tool.of("managed_get_file",
                "Read file content.",
                Map.of("repo_uri", Tool.stringProp("Repository URI"), "branch", Tool.stringProp("Branch"), "path",
                        Tool.stringProp("File path")),
                "repo_uri", "path"));

        return tools;
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        String action = call.name().substring(namespace().length() + 1);
        JsonNode input = call.input();

        try {
            return switch (action) {
                case "get_ticket" -> getTicket(context, call.id(), input);
                case "add_comment" -> addComment(context, call.id(), input);
                case "get_file" -> getFile(context, call.id(), input);
                default -> ToolResult.error(call.id(), "Unknown action: " + action);
            };
        } catch (Exception e) {
            log.error("Managed tool error: {} - {}", action, e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
    }

    private ToolResult getTicket(OperationContext context, String id, JsonNode input) {
        String key = input.get("ticket_key").asText();
        Optional<IssueTrackerProvider> provider = registry.resolveIssueTracker(context, "jira");
        if (provider.isEmpty())
            return ToolResult.error(id, "Jira provider not found");

        Map<String, Object> details = provider.get().getTicketDetails(context, key);
        return ToolResult.success(id, details.toString());
    }

    private ToolResult addComment(OperationContext context, String id, JsonNode input) {
        String key = input.get("ticket_key").asText();
        String comment = input.get("comment").asText();
        Optional<IssueTrackerProvider> provider = registry.resolveIssueTracker(context, "jira");
        if (provider.isEmpty())
            return ToolResult.error(id, "Jira provider not found");

        provider.get().postComment(context, key, comment);
        return ToolResult.success(id, "Comment added");
    }

    private ToolResult getFile(OperationContext context, String id, JsonNode input) throws Exception {
        String repoUri = input.get("repo_uri").asText();
        String branch = input.has("branch") ? input.get("branch").asText() : "main";
        String path = input.get("path").asText();

        Optional<SourceControlProvider> provider = registry.resolveSourceControl(context, repoUri);
        if (provider.isEmpty())
            return ToolResult.error(id, "No provider for " + repoUri);

        String content = provider.get().getFileContent(context, com.aidriven.spi.model.BranchName.of(branch), path);
        return content != null ? ToolResult.success(id, content) : ToolResult.error(id, "File not found");
    }
}
