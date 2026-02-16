package com.aidriven.tool.context;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.Schema;
import com.aidriven.core.model.TicketInfo;
import lombok.RequiredArgsConstructor;

import java.util.List;

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
                        "Get the relevant code context for a ticket based on the configured strategy (Smart or Full Repo). "
                                +
                                "This returns a summarized or full view of the codebase relevant to the issue.",
                        Schema.object()
                                .required("ticket_key", Schema.string("The Jira ticket key"))
                                .required("branch", Schema.string("The git branch to analyze"))));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            if ("code_context_get_context".equals(call.name())) {
                String ticketKey = call.input().get("ticket_key").asText();
                String branch = call.input().get("branch").asText();

                // Construct a minimal TicketInfo for the context service
                // In a real scenario, we might need to fetch the full ticket details if the
                // strategy depends on it (e.g. description)
                // For now, we assume the strategies might just need the key or we pass what we
                // have.
                // NOTE: ContextService logic relies on ticket.isSmartContext() etc.
                // We might need to fetch the ticket here or pass these flags in input.
                // For simplicity, we create a dummy ticket container with the key.
                TicketInfo ticket = new TicketInfo();
                ticket.setTicketKey(ticketKey);

                String context = contextService.buildContext(ticket, branch);
                return context != null
                        ? ToolResult.success(call.id(), context)
                        : ToolResult.error(call.id(), "Failed to generate context");
            }
            return ToolResult.error(call.id(), "Unknown tool: " + call.name());
        } catch (Exception e) {
            return ToolResult.error(call.id(), "Tool execution failed: " + e.getMessage());
        }
    }
}
