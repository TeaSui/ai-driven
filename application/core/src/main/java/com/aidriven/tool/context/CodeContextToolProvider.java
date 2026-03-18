package com.aidriven.tool.context;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Exposes code context gathering as Claude tools.
 */
@RequiredArgsConstructor
public class CodeContextToolProvider implements ToolProvider {

    private final ContextService contextService;

    @Override
    public String namespace() {
        return "code_context";
    }

    @Override
    public List<Tool> toolDefinitions() {
        return List.of(
                Tool.of("code_context_get_context",
                        "Get the relevant code context for a ticket.",
                        Map.of(
                                "ticket_key", Tool.stringProp("The ticket key"),
                                "branch", Tool.stringProp("The git branch to analyze")),
                        "ticket_key", "branch"));
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        try {
            if ("code_context_get_context".equals(call.name())) {
                String ticketKey = call.input().get("ticket_key").asText();
                String branch = call.input().get("branch").asText();

                // Create a minimal TicketInfo for the context service
                TicketInfo ticket = new TicketInfo();
                ticket.setTicketKey(ticketKey);

                String content = contextService.buildContext(context, ticket, BranchName.of(branch));
                return content != null
                        ? ToolResult.success(call.id(), content)
                        : ToolResult.error(call.id(), "Failed to generate context");
            }
            return ToolResult.error(call.id(), "Unknown tool: " + call.name());
        } catch (Exception e) {
            return ToolResult.error(call.id(), "Tool execution failed: " + e.getMessage());
        }
    }
}
