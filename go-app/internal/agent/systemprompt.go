package agent

import (
	"fmt"
	"strings"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

type SystemPromptBuilder struct {
	sb              strings.Builder
	workflowContext *WorkflowContext
}

func NewSystemPromptBuilder() *SystemPromptBuilder {
	b := &SystemPromptBuilder{}
	b.sb.Grow(2048)
	return b
}

func (b *SystemPromptBuilder) AppendPersona() *SystemPromptBuilder {
	b.sb.WriteString("You are an expert AI development assistant embedded in Jira and GitHub.\n")
	b.sb.WriteString("You help developers investigate issues, analyze code, and implement changes.\n")
	b.sb.WriteString("You are replying directly to a user message inside a conversation thread — ")
	b.sb.WriteString("maintain context from previous messages and respond as a direct dialogue partner.\n\n")
	return b
}

func (b *SystemPromptBuilder) WithWorkflowContext(ctx *WorkflowContext) *SystemPromptBuilder {
	b.workflowContext = ctx
	return b
}

func (b *SystemPromptBuilder) AppendContext(request *model.AgentRequest) *SystemPromptBuilder {
	b.sb.WriteString("## Context\n")
	fmt.Fprintf(&b.sb, "- Ticket: %s\n", request.TicketKey)

	if request.TicketInfo != nil {
		if summary, ok := request.TicketInfo["summary"].(string); ok {
			fmt.Fprintf(&b.sb, "- Title: %s\n", summary)
		}
		if desc, ok := request.TicketInfo["description"].(string); ok && desc != "" {
			b.sb.WriteString("- Description:\n")
			b.sb.WriteString(truncate(desc, 2000))
			b.sb.WriteString("\n")
		}
	}

	fmt.Fprintf(&b.sb, "- Platform: %s\n", request.Platform)
	fmt.Fprintf(&b.sb, "- Requested by: %s\n\n", request.CommentAuthor)

	if len(request.PRContext) > 0 {
		b.sb.WriteString("## GitHub PR Line Context\n")
		if filePath, ok := request.PRContext["filePath"]; ok {
			fmt.Fprintf(&b.sb, "- File: `%s`\n", filePath)
		}
		if commitID, ok := request.PRContext["commitId"]; ok {
			fmt.Fprintf(&b.sb, "- Commit: `%s`\n", commitID)
		}
		if diffHunk, ok := request.PRContext["diffHunk"]; ok {
			fmt.Fprintf(&b.sb, "- Code diff:\n```diff\n%s\n```\n", diffHunk)
		}
		b.sb.WriteString("\n")
	}

	return b
}

func (b *SystemPromptBuilder) AppendIntentGuidelines(intent model.CommentIntent) *SystemPromptBuilder {
	switch intent {
	case model.IntentHumanFeedback:
		b.sb.WriteString("## Intent: Feedback on Your Previous Work\n")
		b.sb.WriteString("1. Review the conversation history to understand your prior actions.\n")
		b.sb.WriteString("2. Apply the feedback (modify PR, code, or analysis as requested).\n")
		b.sb.WriteString("3. Summarize exactly what you changed and why.\n\n")
	case model.IntentQuestion:
		b.sb.WriteString("## Intent: Question\n")
		b.sb.WriteString("1. Use tools if needed to gather facts.\n")
		b.sb.WriteString("2. Answer clearly and concisely.\n")
		b.sb.WriteString("3. If the question is ambiguous, ask one targeted clarifying question.\n\n")
	case model.IntentApproval:
		b.sb.WriteString("## Intent: Approval / Rejection\n")
		b.sb.WriteString("The user is responding to a pending approval request.\n")
		b.sb.WriteString("Simply acknowledge their decision; the orchestrator handles execution.\n\n")
	case model.IntentReview:
		b.sb.WriteString("## Intent: Peer Review\n")
		b.sb.WriteString("1. Review the code changes made by the Coder Agent.\n")
		b.sb.WriteString("2. Look for bugs, security issues, style violations, and missed edge cases.\n")
		b.sb.WriteString("3. Provide actionable, specific feedback if changes are needed.\n")
		b.sb.WriteString("4. If the changes are perfect, start your response with 'APPROVED'.\n")
		b.sb.WriteString("5. If changes are needed, start your response with 'REJECTED' followed by details.\n\n")
	case model.IntentTest:
		b.sb.WriteString("## Intent: Automated Testing\n")
		b.sb.WriteString("1. Verify code changes by generating and running test cases.\n")
		b.sb.WriteString("2. Look for edge cases, performance issues, and contract violations.\n")
		b.sb.WriteString("3. If tests fail, provide relevant diagnostics and error logs.\n")
		b.sb.WriteString("4. If all tests pass and coverage is sufficient, start your response with 'PASSED'.\n")
		b.sb.WriteString("5. If tests fail or coverage is lacking, start your response with 'FAILED' followed by details.\n\n")
	default:
		b.sb.WriteString("## Guidelines\n")
		b.sb.WriteString("1. Use tools to investigate before acting.\n")
		b.sb.WriteString("2. Be precise and concise — favour quality over verbosity.\n")
		b.sb.WriteString("3. Explain your reasoning before making code changes.\n")
		b.sb.WriteString("4. Ask one targeted clarifying question rather than guessing when uncertain.\n")
		b.sb.WriteString("5. Always end with actionable, concrete results.\n\n")
	}
	return b
}

func (b *SystemPromptBuilder) Build() string {
	if b.workflowContext != nil {
		b.sb.WriteString(b.workflowContext.ToPromptSection())
	}
	return b.sb.String()
}

func truncate(text string, maxLen int) string {
	if len(text) <= maxLen {
		return text
	}
	return text[:maxLen] + "\u2026"
}
