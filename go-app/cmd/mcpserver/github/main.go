package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"

	"github.com/AirdropToTheMoon/ai-driven/internal/provider/github"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
)

func main() {
	zerolog.TimeFieldFormat = zerolog.TimeFormatUnix
	log.Logger = zerolog.New(os.Stderr).With().Timestamp().Logger()

	owner := os.Getenv("GITHUB_OWNER")
	repo := os.Getenv("GITHUB_REPO")
	token := os.Getenv("GITHUB_TOKEN")

	if owner == "" || repo == "" || token == "" {
		log.Fatal().Msg("GITHUB_OWNER, GITHUB_REPO, and GITHUB_TOKEN environment variables are required")
	}

	client := github.NewClient(owner, repo, token)

	s := server.NewMCPServer(
		"github-mcp-server",
		"1.0.0",
		server.WithToolCapabilities(false),
	)

	registerGitHubTools(s, client)

	log.Info().Msg("GitHub MCP server starting via stdio")
	if err := server.ServeStdio(s); err != nil {
		log.Fatal().Err(err).Msg("GitHub MCP server failed")
	}
}

func registerGitHubTools(s *server.MCPServer, client *github.Client) {
	// get_default_branch
	s.AddTool(
		mcp.NewTool("get_default_branch",
			mcp.WithDescription("Get the default branch name of the repository."),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:        "Get Default Branch",
				ReadOnlyHint: mcp.ToBoolPtr(true),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			branch, err := client.GetDefaultBranch(ctx, nil)
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to get default branch: %v", err)), nil
			}
			return newTextResult(branch.Value()), nil
		},
	)

	// get_file
	s.AddTool(
		mcp.NewTool("get_file",
			mcp.WithDescription("Retrieve the content of a file from the repository."),
			mcp.WithString("file_path",
				mcp.Required(),
				mcp.Description("Path to the file in the repository."),
			),
			mcp.WithString("branch",
				mcp.Required(),
				mcp.Description("Branch to read from."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:        "Get File",
				ReadOnlyHint: mcp.ToBoolPtr(true),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			filePath, err := req.RequireString("file_path")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			branchStr, err := req.RequireString("branch")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			branch, err := spi.NewBranchName(branchStr)
			if err != nil {
				return newErrorResult(fmt.Sprintf("invalid branch: %v", err)), nil
			}

			content, err := client.GetFileContent(ctx, nil, branch, filePath)
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to get file: %v", err)), nil
			}
			if content == "" {
				return newTextResult("File not found or empty."), nil
			}
			return newTextResult(content), nil
		},
	)

	// list_files
	s.AddTool(
		mcp.NewTool("list_files",
			mcp.WithDescription("List files in the repository tree."),
			mcp.WithString("branch",
				mcp.Required(),
				mcp.Description("Branch to list files from."),
			),
			mcp.WithString("path",
				mcp.Description("Subdirectory path to filter by. Defaults to repository root."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:        "List Files",
				ReadOnlyHint: mcp.ToBoolPtr(true),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			branchStr, err := req.RequireString("branch")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			branch, err := spi.NewBranchName(branchStr)
			if err != nil {
				return newErrorResult(fmt.Sprintf("invalid branch: %v", err)), nil
			}
			path := req.GetString("path", "")

			files, err := client.GetFileTree(ctx, nil, branch, path)
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to list files: %v", err)), nil
			}
			if len(files) == 0 {
				return newTextResult("No files found."), nil
			}

			jsonBytes, err := json.MarshalIndent(files, "", "  ")
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to marshal file list: %v", err)), nil
			}
			return newTextResult(string(jsonBytes)), nil
		},
	)

	// create_branch
	s.AddTool(
		mcp.NewTool("create_branch",
			mcp.WithDescription("Create a new branch in the repository."),
			mcp.WithString("branch_name",
				mcp.Required(),
				mcp.Description("Name of the new branch to create."),
			),
			mcp.WithString("from_branch",
				mcp.Required(),
				mcp.Description("Source branch to create from."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:           "Create Branch",
				ReadOnlyHint:    mcp.ToBoolPtr(false),
				DestructiveHint: mcp.ToBoolPtr(false),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			branchName, err := req.RequireString("branch_name")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			fromBranch, err := req.RequireString("from_branch")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}

			source, err := spi.NewBranchName(fromBranch)
			if err != nil {
				return newErrorResult(fmt.Sprintf("invalid from_branch: %v", err)), nil
			}
			target, err := spi.NewBranchName(branchName)
			if err != nil {
				return newErrorResult(fmt.Sprintf("invalid branch_name: %v", err)), nil
			}

			if err := client.CreateBranch(ctx, nil, source, target); err != nil {
				return newErrorResult(fmt.Sprintf("failed to create branch: %v", err)), nil
			}
			return newTextResult(fmt.Sprintf("Branch '%s' created from '%s'.", branchName, fromBranch)), nil
		},
	)

	// push_file
	s.AddTool(
		mcp.NewTool("push_file",
			mcp.WithDescription("Push a single file to a branch with a commit message."),
			mcp.WithString("branch",
				mcp.Required(),
				mcp.Description("Branch to push to."),
			),
			mcp.WithString("file_path",
				mcp.Required(),
				mcp.Description("Path of the file in the repository."),
			),
			mcp.WithString("content",
				mcp.Required(),
				mcp.Description("Content of the file."),
			),
			mcp.WithString("commit_message",
				mcp.Required(),
				mcp.Description("Commit message for the push."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:           "Push File",
				ReadOnlyHint:    mcp.ToBoolPtr(false),
				DestructiveHint: mcp.ToBoolPtr(true),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			branchStr, err := req.RequireString("branch")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			filePath, err := req.RequireString("file_path")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			content, err := req.RequireString("content")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			commitMsg, err := req.RequireString("commit_message")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}

			branch, err := spi.NewBranchName(branchStr)
			if err != nil {
				return newErrorResult(fmt.Sprintf("invalid branch: %v", err)), nil
			}

			files := []spi.RepoFile{
				{
					Path:      filePath,
					Content:   content,
					Operation: "add",
				},
			}

			commitSHA, err := client.PushFiles(ctx, nil, branch, files, commitMsg)
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to push file: %v", err)), nil
			}
			return newTextResult(fmt.Sprintf("File '%s' pushed to '%s'. Commit: %s", filePath, branchStr, commitSHA)), nil
		},
	)

	// create_pr
	s.AddTool(
		mcp.NewTool("create_pr",
			mcp.WithDescription("Create a pull request."),
			mcp.WithString("title",
				mcp.Required(),
				mcp.Description("Title of the pull request."),
			),
			mcp.WithString("source_branch",
				mcp.Required(),
				mcp.Description("Source (head) branch for the pull request."),
			),
			mcp.WithString("target_branch",
				mcp.Required(),
				mcp.Description("Target (base) branch for the pull request."),
			),
			mcp.WithString("description",
				mcp.Description("Body/description of the pull request."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:           "Create Pull Request",
				ReadOnlyHint:    mcp.ToBoolPtr(false),
				DestructiveHint: mcp.ToBoolPtr(false),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			title, err := req.RequireString("title")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			sourceBranchStr, err := req.RequireString("source_branch")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			targetBranchStr, err := req.RequireString("target_branch")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			description := req.GetString("description", "")

			source, err := spi.NewBranchName(sourceBranchStr)
			if err != nil {
				return newErrorResult(fmt.Sprintf("invalid source_branch: %v", err)), nil
			}
			target, err := spi.NewBranchName(targetBranchStr)
			if err != nil {
				return newErrorResult(fmt.Sprintf("invalid target_branch: %v", err)), nil
			}

			result, err := client.OpenPullRequest(ctx, nil, title, description, source, target)
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to create PR: %v", err)), nil
			}

			response := fmt.Sprintf("Pull request created: %s\nURL: %s\nID: %s", result.Title, result.URL, result.ID)
			return newTextResult(response), nil
		},
	)

	// list_prs
	s.AddTool(
		mcp.NewTool("list_prs",
			mcp.WithDescription("List open pull requests in the repository."),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:        "List Pull Requests",
				ReadOnlyHint: mcp.ToBoolPtr(true),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			prs, err := client.ListPullRequests(ctx)
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to list pull requests: %v", err)), nil
			}
			if len(prs) == 0 {
				return newTextResult("No open pull requests found."), nil
			}

			jsonBytes, err := json.MarshalIndent(prs, "", "  ")
			if err != nil {
				return newErrorResult(fmt.Sprintf("failed to marshal PR list: %v", err)), nil
			}
			return newTextResult(string(jsonBytes)), nil
		},
	)

	// add_pr_comment
	s.AddTool(
		mcp.NewTool("add_pr_comment",
			mcp.WithDescription("Add a comment to a pull request."),
			mcp.WithString("pr_id",
				mcp.Required(),
				mcp.Description("The pull request number (as a string)."),
			),
			mcp.WithString("comment",
				mcp.Required(),
				mcp.Description("The comment text to add."),
			),
			mcp.WithToolAnnotation(mcp.ToolAnnotation{
				Title:           "Add PR Comment",
				ReadOnlyHint:    mcp.ToBoolPtr(false),
				DestructiveHint: mcp.ToBoolPtr(false),
			}),
		),
		func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			prID, err := req.RequireString("pr_id")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			comment, err := req.RequireString("comment")
			if err != nil {
				return newErrorResult(err.Error()), nil
			}
			if prID == "" || comment == "" {
				return newErrorResult("pr_id and comment must not be empty"), nil
			}

			if err := client.AddPRComment(ctx, nil, prID, "", comment, ""); err != nil {
				return newErrorResult(fmt.Sprintf("failed to add comment to PR %s: %v", prID, err)), nil
			}
			return newTextResult(fmt.Sprintf("Comment added to PR #%s.", prID)), nil
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
