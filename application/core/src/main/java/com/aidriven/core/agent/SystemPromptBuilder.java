package com.aidriven.core.agent;

import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.CommentIntent;

import java.util.Optional;

/**
 * Fluent builder for constructing the complex AI system prompt.
 * Keeps the AgentOrchestrator clean and makes prompt sections easily testable.
 */
public class SystemPromptBuilder {

    private final StringBuilder sb = new StringBuilder(2048);
    private WorkflowContextProvider.WorkflowContext workflowContext;

    public SystemPromptBuilder appendPersona() {
        sb.append("You are an expert AI development assistant embedded in Jira and GitHub.\n");
        sb.append("You help developers investigate issues, analyze code, and implement changes.\n");
        sb.append("You are replying directly to a user message inside a conversation thread — ");
        sb.append("maintain context from previous messages and respond as a direct dialogue partner.\n\n");
        return this;
    }

    /**
     * Sets the workflow context from prior automated work (e.g., ai-generate PRs).
     * Call this before build() to include PR context in the prompt.
     */
    public SystemPromptBuilder withWorkflowContext(WorkflowContextProvider.WorkflowContext context) {
        this.workflowContext = context;
        return this;
    }

    /**
     * Sets the workflow context from an Optional.
     */
    public SystemPromptBuilder withWorkflowContext(Optional<WorkflowContextProvider.WorkflowContext> context) {
        context.ifPresent(ctx -> this.workflowContext = ctx);
        return this;
    }

    public SystemPromptBuilder appendContext(AgentRequest request) {
        sb.append("## Context\n");
        sb.append("- Ticket: ").append(request.ticketKey()).append("\n");
        if (request.ticketInfo() != null) {
            sb.append("- Title: ").append(request.ticketInfo().getSummary()).append("\n");
            if (request.ticketInfo().getDescription() != null) {
                sb.append("- Description:\n");
                sb.append(truncate(request.ticketInfo().getDescription(), 2000)).append("\n");
            }
        }
        sb.append("- Platform: ").append(request.platform()).append("\n");
        sb.append("- Requested by: ").append(request.commentAuthor()).append("\n\n");

        if (request.prContext() != null && !request.prContext().isEmpty()) {
            sb.append("## GitHub PR Line Context\n");
            if (request.prContext().containsKey("filePath")) {
                sb.append("- File: `").append(request.prContext().get("filePath")).append("`\n");
            }
            if (request.prContext().containsKey("commitId")) {
                sb.append("- Commit: `").append(request.prContext().get("commitId")).append("`\n");
            }
            if (request.prContext().containsKey("diffHunk")) {
                sb.append("- Code diff:\n```diff\n")
                        .append(request.prContext().get("diffHunk"))
                        .append("\n```\n");
            }
            sb.append("\n");
        }
        return this;
    }

    public SystemPromptBuilder appendIntentGuidelines(CommentIntent intent) {
        switch (intent) {
            case HUMAN_FEEDBACK -> {
                sb.append("## Intent: Feedback on Your Previous Work\n");
                sb.append("1. Review the conversation history to understand your prior actions.\n");
                sb.append("2. Apply the feedback (modify PR, code, or analysis as requested).\n");
                sb.append("3. Summarize exactly what you changed and why.\n\n");
            }
            case QUESTION -> {
                sb.append("## Intent: Question\n");
                sb.append("1. Use tools if needed to gather facts.\n");
                sb.append("2. Answer clearly and concisely.\n");
                sb.append("3. If the question is ambiguous, ask one targeted clarifying question.\n\n");
            }
            case APPROVAL -> {
                sb.append("## Intent: Approval / Rejection\n");
                sb.append("The user is responding to a pending approval request.\n");
                sb.append("Simply acknowledge their decision; the orchestrator handles execution.\n\n");
            }
            case REVIEW -> {
                sb.append("## Intent: Peer Review\n");
                sb.append("1. Review the code changes made by the Coder Agent.\n");
                sb.append("2. Look for bugs, security issues, style violations, and missed edge cases.\n");
                sb.append("3. Provide actionable, specific feedback if changes are needed.\n");
                sb.append("4. If the changes are perfect, start your response with 'APPROVED'.\n");
                sb.append("5. If changes are needed, start your response with 'REJECTED' followed by details.\n\n");
            }
            case TEST -> {
                sb.append("## Intent: Automated Testing\n");
                sb.append("1. Verify code changes by generating and running test cases.\n");
                sb.append("2. Look for edge cases, performance issues, and contract violations.\n");
                sb.append("3. If tests fail, provide relevant diagnostics and error logs.\n");
                sb.append("4. If all tests pass and coverage is sufficient, start your response with 'PASSED'.\n");
                sb.append(
                        "5. If tests fail or coverage is lacking, start your response with 'FAILED' followed by details.\n\n");
            }
            default -> {
                sb.append("## Guidelines\n");
                sb.append("1. Use tools to investigate before acting.\n");
                sb.append("2. Be precise and concise — favour quality over verbosity.\n");
                sb.append("3. Explain your reasoning before making code changes.\n");
                sb.append("4. Ask one targeted clarifying question rather than guessing when uncertain.\n");
                sb.append("5. Always end with actionable, concrete results.\n\n");
            }
        }
        return this;
    }

    public String build() {
        // Append workflow context at the end if available
        if (workflowContext != null) {
            sb.append(workflowContext.toPromptSection());
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
