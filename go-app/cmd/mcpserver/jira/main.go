package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"

	"github.com/AirdropToTheMoon/ai-driven/internal/provider/jira"
	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
)

func main() {
	zerolog.TimeFieldFormat = zerolog.TimeFormatUnix
	log.Logger = zerolog.New(os.Stderr).With().Timestamp().Logger()

	baseURL := os.Getenv("JIRA_BASE_URL")
	email := os.Getenv("JIRA_EMAIL")
	apiToken := os.Getenv("JIRA_API_TOKEN")

	if baseURL == "" || email == "" || apiToken == "" {
		log.Fatal().Msg("JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN environment variables are required")
	}

	client := jira.NewClient(baseURL, email, apiToken)

	s := server.NewMCPServer(
		"jira-mcp-server",
		"1.0.0",
		server.WithToolCapabilities(false),
	)

	registerJiraTools(s, client)

	log.Info().Msg("Jira MCP server starting via stdio")
	if err := server.ServeStdio(s); err != nil {
		log.Fatal().Err(err).Msg("Jira MCP server failed")
	}
}

func registerJiraTools(s *server.MCPServer, client *jira.Client) {
	// get_ticket
	s.AddTool(
		mcp.NewTool("get_ticket",
			mcp.WithDescription("Retrieve full details of a Jira issue by its key (e.g. PROJ-123)."),
			mcp.WithString("ticket_key",
				mcp.Required(),
				mcp.Description("The Jira issue key, e.g. PROJ-123."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:        "Get Jira Ticket",
				ReadOnlyHint: mcp.ToBoolPtr(true),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			ticketKey, err := req.RequireString("ticket_key")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			if ticketKey == "" {
				return newErrorResult("ticket_key must not be empty"), nil
			}

			result, err := client.GetTicketDetails(ctx, nil, ticketKey)
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to get ticket %s: %v", ticketKey, err)), nil
			}

			jsonBytes, err := json.MarshalIndent(result, "", "  ")
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to marshal ticket: %v", err)), nil
			}
			return newTextResult(string(jsonBytes)), nil
		},
	)

	// add_comment
	s.AddTool(
		mcp.NewTool("add_comment",
			mcp.WithDescription("Add a comment to a Jira issue."),
			mcp.WithString("ticket_key",
				mcp.Required(),
				mcp.Description("The Jira issue key, e.g. PROJ-123."),
			),
			mcp.WithString("comment",
				mcp.Required(),
				mcp.Description("The comment text to add."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:           "Add Jira Comment",
				ReadOnlyHint:    mcp.ToBoolPtr(false),
				DestructiveHint: mcp.ToBoolPtr(false),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			ticketKey, err := req.RequireString("ticket_key")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			comment, err := req.RequireString("comment")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			if ticketKey == "" || comment == "" {
				return newErrorResult("ticket_key and comment must not be empty"), nil
			}

			if err := client.PostComment(ctx, nil, ticketKey, comment); err != nil {
				return newErrorResult(fmt.Sprintf("failed to add comment to %s: %v", ticketKey, err)), nil
			}
			return newTextResult(fmt.Sprintf("Comment added to %s.", ticketKey)), nil
		},
	)

	// update_labels
	s.AddTool(
		mcp.NewTool("update_labels",
			mcp.WithDescription("Add and/or remove labels on a Jira issue."),
			mcp.WithString("ticket_key",
				mcp.Required(),
				mcp.Description("The Jira issue key, e.g. PROJ-123."),
			),
			mcp.WithArray("add_labels",
				mcp.Description("Labels to add to the issue."),
				mcp.WithStringItems(),
			),
			mcp.WithArray("remove_labels",
				mcp.Description("Labels to remove from the issue."),
				mcp.WithStringItems(),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:           "Update Jira Labels",
				ReadOnlyHint:    mcp.ToBoolPtr(false),
				DestructiveHint: mcp.ToBoolPtr(false),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			ticketKey, err := req.RequireString("ticket_key")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			if ticketKey == "" {
				return newErrorResult("ticket_key must not be empty"), nil
			}

			addLabels := req.GetStringSlice("add_labels", nil)
			removeLabels := req.GetStringSlice("remove_labels", nil)

			if len(addLabels) == 0 && len(removeLabels) == 0 {
				return newErrorResult("at least one of add_labels or remove_labels must be provided"), nil
			}

			if err := client.UpdateLabels(ctx, nil, ticketKey, addLabels, removeLabels); err != nil {
				return newErrorResult(fmt.Sprintf("failed to update labels on %s: %v", ticketKey, err)), nil
			}
			return newTextResult(fmt.Sprintf("Labels updated on %s.", ticketKey)), nil
		},
	)

	// update_status
	s.AddTool(
		mcp.NewTool("update_status",
			mcp.WithDescription("Transition a Jira issue to a new status (e.g. 'In Progress', 'Done')."),
			mcp.WithString("ticket_key",
				mcp.Required(),
				mcp.Description("The Jira issue key, e.g. PROJ-123."),
			),
			mcp.WithString("status",
				mcp.Required(),
				mcp.Description("The target status name to transition to."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:           "Update Jira Status",
				ReadOnlyHint:    mcp.ToBoolPtr(false),
				DestructiveHint: mcp.ToBoolPtr(false),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			ticketKey, err := req.RequireString("ticket_key")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			status, err := req.RequireString("status")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			if ticketKey == "" || status == "" {
				return newErrorResult("ticket_key and status must not be empty"), nil
			}

			if err := client.UpdateStatus(ctx, nil, ticketKey, status); err != nil {
				return newErrorResult(fmt.Sprintf("failed to update status on %s: %v", ticketKey, err)), nil
			}
			return newTextResult(fmt.Sprintf("Status of %s updated to '%s'.", ticketKey, status)), nil
		},
	)
}

func newTextResult(text string) *mcp.CallToolResult {
	return &mcp.CallToolResult{
		Content: []mcp.Content{
			mcp.TextContent{
				Type: "text",
				Text: text,
			},
		},
	}
}

func newErrorResult(msg string) *mcp.CallToolResult {
	return &mcp.CallToolResult{
		Content: []mcp.Content{
			mcp.TextContent{
				Type: "text",
				Text: msg,
			},
		},
		IsError: true,
	}
}
