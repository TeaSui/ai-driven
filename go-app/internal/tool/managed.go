package tool

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

const managedNamespace = "managed"

// ManagedMcpToolProvider provides tools that route to dynamically-resolved providers via the registry.
type ManagedMcpToolProvider struct {
	registry *spi.ProviderRegistry
}

// NewManagedMcpToolProvider creates a new ManagedMcpToolProvider.
func NewManagedMcpToolProvider(registry *spi.ProviderRegistry) *ManagedMcpToolProvider {
	return &ManagedMcpToolProvider{registry: registry}
}

func (p *ManagedMcpToolProvider) Namespace() string { return managedNamespace }

func (p *ManagedMcpToolProvider) MaxOutputChars() int { return 50_000 }

func (p *ManagedMcpToolProvider) Definitions() []Def {
	return []Def{
		{
			Name:        "managed_get_ticket",
			Description: "Retrieve ticket details from a managed issue tracker provider.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"ticket_key": map[string]any{"type": "string", "description": "The ticket key (e.g., PROJ-123)."},
				},
				"required": []string{"ticket_key"},
			},
		},
		{
			Name:        "managed_add_comment",
			Description: "Add a comment to a ticket via a managed issue tracker provider.",
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
			Name:        "managed_get_file",
			Description: "Retrieve a file from a repository resolved by URI.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"repo_uri": map[string]any{"type": "string", "description": "Repository URI to resolve the source control provider."},
					"path":     map[string]any{"type": "string", "description": "Path to the file in the repository."},
					"branch":   map[string]any{"type": "string", "description": "Branch to read from."},
				},
				"required": []string{"repo_uri", "path"},
			},
		},
	}
}

func (p *ManagedMcpToolProvider) Execute(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	suffix := strings.TrimPrefix(call.Name, managedNamespace+"_")
	switch suffix {
	case "get_ticket":
		return p.getTicket(ctx, op, call)
	case "add_comment":
		return p.addComment(ctx, op, call)
	case "get_file":
		return p.getFile(ctx, op, call)
	default:
		return ErrorOutput(call.ID, fmt.Sprintf("unknown tool: %s", call.Name))
	}
}

func (p *ManagedMcpToolProvider) resolveIssueTracker(ticketKey string) (spi.IssueTrackerProvider, error) {
	tk, err := spi.NewTicketKey(ticketKey)
	if err != nil {
		return nil, fmt.Errorf("invalid ticket key: %w", err)
	}
	provider, ok := p.registry.ResolveIssueTracker(tk.ProjectKey())
	if !ok {
		// Fall back: try to find any registered issue tracker.
		provider, ok = p.registry.ResolveIssueTracker("default")
		if !ok {
			return nil, fmt.Errorf("no issue tracker provider found for project '%s'", tk.ProjectKey())
		}
	}
	return provider, nil
}

func (p *ManagedMcpToolProvider) getTicket(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	ticketKey := call.String("ticket_key")
	if ticketKey == "" {
		return ErrorOutput(call.ID, "ticket_key is required")
	}
	provider, err := p.resolveIssueTracker(ticketKey)
	if err != nil {
		return ErrorOutput(call.ID, err.Error())
	}
	details, err := provider.GetTicketDetails(ctx, op, ticketKey)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to get ticket: %v", err))
	}
	b, err := json.MarshalIndent(details, "", "  ")
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to format ticket details: %v", err))
	}
	return SuccessOutput(call.ID, string(b))
}

func (p *ManagedMcpToolProvider) addComment(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	ticketKey := call.String("ticket_key")
	comment := call.String("comment")
	if ticketKey == "" || comment == "" {
		return ErrorOutput(call.ID, "ticket_key and comment are required")
	}
	provider, err := p.resolveIssueTracker(ticketKey)
	if err != nil {
		return ErrorOutput(call.ID, err.Error())
	}
	if err := provider.PostComment(ctx, op, ticketKey, comment); err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to add comment: %v", err))
	}
	return SuccessOutput(call.ID, fmt.Sprintf("Comment added to %s.", ticketKey))
}

func (p *ManagedMcpToolProvider) getFile(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	repoURI := call.String("repo_uri")
	path := call.String("path")
	if repoURI == "" || path == "" {
		return ErrorOutput(call.ID, "repo_uri and path are required")
	}
	provider, ok := p.registry.ResolveSourceControlByURI(repoURI)
	if !ok {
		return ErrorOutput(call.ID, fmt.Sprintf("no source control provider found for URI '%s'", repoURI))
	}

	branchStr := call.String("branch")
	if branchStr == "" {
		// Use the provider's default branch.
		defaultBranch, err := provider.GetDefaultBranch(ctx, op)
		if err != nil {
			return ErrorOutput(call.ID, fmt.Sprintf("failed to resolve default branch: %v", err))
		}
		content, err := provider.GetFileContent(ctx, op, defaultBranch, path)
		if err != nil {
			return ErrorOutput(call.ID, fmt.Sprintf("failed to get file: %v", err))
		}
		return SuccessOutput(call.ID, content)
	}

	branch, err := spi.NewBranchName(branchStr)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid branch: %v", err))
	}
	content, err := provider.GetFileContent(ctx, op, branch, path)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to get file: %v", err))
	}
	return SuccessOutput(call.ID, content)
}
