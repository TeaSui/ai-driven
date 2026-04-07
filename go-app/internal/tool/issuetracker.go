package tool

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

const issueTrackerNamespace = "issue_tracker"

// IssueTrackerToolProvider exposes issue tracking operations as AI tools.
type IssueTrackerToolProvider struct {
	client spi.IssueTrackerProvider
}

// NewIssueTrackerToolProvider creates a new IssueTrackerToolProvider.
func NewIssueTrackerToolProvider(client spi.IssueTrackerProvider) *IssueTrackerToolProvider {
	return &IssueTrackerToolProvider{client: client}
}

func (p *IssueTrackerToolProvider) Namespace() string { return issueTrackerNamespace }

func (p *IssueTrackerToolProvider) MaxOutputChars() int { return 50_000 }

func (p *IssueTrackerToolProvider) Definitions() []Def {
	return []Def{
		{
			Name:        "issue_tracker_get_ticket",
			Description: "Retrieve the details of a ticket from the issue tracker.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"ticket_key": map[string]any{"type": "string", "description": "The ticket key (e.g., PROJ-123)."},
				},
				"required": []string{"ticket_key"},
			},
		},
		{
			Name:        "issue_tracker_add_comment",
			Description: "Add a comment to a ticket in the issue tracker.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"ticket_key": map[string]any{"type": "string", "description": "The ticket key (e.g., PROJ-123)."},
					"comment":    map[string]any{"type": "string", "description": "Comment text to add."},
				},
				"required": []string{"ticket_key", "comment"},
			},
		},
		{
			Name:        "issue_tracker_update_status",
			Description: "Update the status of a ticket in the issue tracker.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"ticket_key": map[string]any{"type": "string", "description": "The ticket key (e.g., PROJ-123)."},
					"status":     map[string]any{"type": "string", "description": "New status to set on the ticket."},
				},
				"required": []string{"ticket_key", "status"},
			},
		},
	}
}

func (p *IssueTrackerToolProvider) Execute(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	suffix := strings.TrimPrefix(call.Name, issueTrackerNamespace+"_")
	switch suffix {
	case "get_ticket":
		return p.getTicket(ctx, op, call)
	case "add_comment":
		return p.addComment(ctx, op, call)
	case "update_status":
		return p.updateStatus(ctx, op, call)
	default:
		return ErrorOutput(call.ID, fmt.Sprintf("unknown tool: %s", call.Name))
	}
}

func (p *IssueTrackerToolProvider) getTicket(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	ticketKey := call.String("ticket_key")
	if ticketKey == "" {
		return ErrorOutput(call.ID, "ticket_key is required")
	}
	details, err := p.client.GetTicketDetails(ctx, op, ticketKey)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to get ticket: %v", err))
	}
	b, err := json.MarshalIndent(details, "", "  ")
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to format ticket details: %v", err))
	}
	return SuccessOutput(call.ID, string(b))
}

func (p *IssueTrackerToolProvider) addComment(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	ticketKey := call.String("ticket_key")
	comment := call.String("comment")
	if ticketKey == "" || comment == "" {
		return ErrorOutput(call.ID, "ticket_key and comment are required")
	}
	if err := p.client.PostComment(ctx, op, ticketKey, comment); err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to add comment: %v", err))
	}
	return SuccessOutput(call.ID, fmt.Sprintf("Comment added to %s.", ticketKey))
}

func (p *IssueTrackerToolProvider) updateStatus(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	ticketKey := call.String("ticket_key")
	status := call.String("status")
	if ticketKey == "" || status == "" {
		return ErrorOutput(call.ID, "ticket_key and status are required")
	}
	if err := p.client.UpdateStatus(ctx, op, ticketKey, status); err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to update status: %v", err))
	}
	return SuccessOutput(call.ID, fmt.Sprintf("Status of %s updated to '%s'.", ticketKey, status))
}
