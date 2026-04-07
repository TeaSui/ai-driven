package tool

import (
	"context"
	"encoding/json"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"

	appctx "github.com/AirdropToTheMoon/ai-driven/internal/context"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

const sourceControlNamespace = "source_control"

// SourceControlToolProvider exposes source control operations as AI tools.
type SourceControlToolProvider struct {
	client        spi.SourceControlProvider
	defaultBranch string
}

// NewSourceControlToolProvider creates a new SourceControlToolProvider.
func NewSourceControlToolProvider(client spi.SourceControlProvider, defaultBranch string) *SourceControlToolProvider {
	return &SourceControlToolProvider{
		client:        client,
		defaultBranch: defaultBranch,
	}
}

func (p *SourceControlToolProvider) Namespace() string { return sourceControlNamespace }

func (p *SourceControlToolProvider) MaxOutputChars() int { return 100_000 }

func (p *SourceControlToolProvider) Definitions() []Def {
	return []Def{
		{
			Name:        "source_control_get_file",
			Description: "Retrieve the content of a single file from the repository.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"file_path": map[string]any{"type": "string", "description": "Path to the file in the repository."},
					"branch":    map[string]any{"type": "string", "description": "Branch to read from. Defaults to the repository default branch."},
				},
				"required": []string{"file_path"},
			},
		},
		{
			Name:        "source_control_search_files",
			Description: "Search for files in the repository by filename or path pattern.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"query": map[string]any{"type": "string", "description": "Search query for file names or paths."},
				},
				"required": []string{"query"},
			},
		},
		{
			Name:        "source_control_get_file_tree",
			Description: "List files and directories in the repository tree.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"branch": map[string]any{"type": "string", "description": "Branch to read the tree from."},
					"path":   map[string]any{"type": "string", "description": "Subdirectory path to list. Defaults to repository root."},
				},
				"required": []string{"branch"},
			},
		},
		{
			Name:        "source_control_create_branch",
			Description: "Create a new branch in the repository.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"branch_name": map[string]any{"type": "string", "description": "Name of the new branch to create."},
					"from_branch": map[string]any{"type": "string", "description": "Source branch to create from. Defaults to the repository default branch."},
				},
				"required": []string{"branch_name"},
			},
		},
		{
			Name:        "source_control_commit_files",
			Description: "Commit one or more file changes to a branch.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"branch_name":    map[string]any{"type": "string", "description": "Branch to commit to."},
					"commit_message": map[string]any{"type": "string", "description": "Commit message."},
					"files": map[string]any{
						"type":        "array",
						"description": "List of files to commit.",
						"items": map[string]any{
							"type": "object",
							"properties": map[string]any{
								"path":      map[string]any{"type": "string", "description": "File path."},
								"content":   map[string]any{"type": "string", "description": "File content."},
								"operation": map[string]any{"type": "string", "enum": []string{"add", "modify", "delete"}, "description": "Operation type."},
							},
							"required": []string{"path", "content", "operation"},
						},
					},
				},
				"required": []string{"branch_name", "commit_message", "files"},
			},
		},
		{
			Name:        "source_control_create_pr",
			Description: "Create a pull request.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"title":              map[string]any{"type": "string", "description": "Pull request title."},
					"source_branch":      map[string]any{"type": "string", "description": "Source branch for the pull request."},
					"description":        map[string]any{"type": "string", "description": "Pull request description."},
					"destination_branch": map[string]any{"type": "string", "description": "Target branch. Defaults to the repository default branch."},
				},
				"required": []string{"title", "source_branch"},
			},
		},
		{
			Name:        "source_control_list_pull_requests",
			Description: "List open pull requests in the repository.",
			InputSchema: map[string]any{
				"type":       "object",
				"properties": map[string]any{},
			},
		},
		{
			Name:        "source_control_get_ci_logs",
			Description: "Retrieve CI/CD pipeline logs for a given run.",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"run_id":        map[string]any{"type": "string", "description": "The CI run or pipeline ID."},
					"max_log_chars": map[string]any{"type": "integer", "description": "Maximum number of characters to return from the log."},
				},
				"required": []string{"run_id"},
			},
		},
		{
			Name:        "source_control_view_file_outline",
			Description: "View the structural outline (classes, functions, methods) of a source file. (Stub — AST analysis not yet ported.)",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"file_path": map[string]any{"type": "string", "description": "Path to the file."},
					"branch":    map[string]any{"type": "string", "description": "Branch to read from."},
					"max_depth": map[string]any{"type": "integer", "description": "Maximum depth of nesting to include."},
				},
				"required": []string{"file_path"},
			},
		},
		{
			Name:        "source_control_search_grep",
			Description: "Search file contents using text or regex patterns. (Stub — not yet ported.)",
			InputSchema: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"query":     map[string]any{"type": "string", "description": "Search pattern."},
					"file_path": map[string]any{"type": "string", "description": "Restrict search to a specific file path."},
					"branch":    map[string]any{"type": "string", "description": "Branch to search in."},
					"is_regex":  map[string]any{"type": "boolean", "description": "Whether the query is a regular expression."},
				},
				"required": []string{"query"},
			},
		},
	}
}

func (p *SourceControlToolProvider) Execute(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	suffix := strings.TrimPrefix(call.Name, sourceControlNamespace+"_")
	switch suffix {
	case "get_file":
		return p.getFile(ctx, op, call)
	case "search_files":
		return p.searchFiles(ctx, op, call)
	case "get_file_tree":
		return p.getFileTree(ctx, op, call)
	case "create_branch":
		return p.createBranch(ctx, op, call)
	case "commit_files":
		return p.commitFiles(ctx, op, call)
	case "create_pr":
		return p.createPR(ctx, op, call)
	case "list_pull_requests":
		return p.listPullRequests(call)
	case "get_ci_logs":
		return p.getCILogs(call)
	case "view_file_outline":
		return p.viewFileOutline(ctx, op, call)
	case "search_grep":
		return p.searchGrep(ctx, op, call)
	default:
		return ErrorOutput(call.ID, fmt.Sprintf("unknown tool: %s", call.Name))
	}
}

func (p *SourceControlToolProvider) resolveBranch(branchStr string) (spi.BranchName, error) {
	if branchStr == "" {
		branchStr = p.defaultBranch
	}
	return spi.NewBranchName(branchStr)
}

func (p *SourceControlToolProvider) getFile(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	filePath := call.String("file_path")
	if filePath == "" {
		return ErrorOutput(call.ID, "file_path is required")
	}
	branch, err := p.resolveBranch(call.String("branch"))
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid branch: %v", err))
	}
	content, err := p.client.GetFileContent(ctx, op, branch, filePath)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to get file: %v", err))
	}
	return SuccessOutput(call.ID, content)
}

func (p *SourceControlToolProvider) searchFiles(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	query := call.String("query")
	if query == "" {
		return ErrorOutput(call.ID, "query is required")
	}
	files, err := p.client.SearchFiles(ctx, op, query)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("search failed: %v", err))
	}
	if len(files) == 0 {
		return SuccessOutput(call.ID, "No files found matching the query.")
	}
	return SuccessOutput(call.ID, strings.Join(files, "\n"))
}

func (p *SourceControlToolProvider) getFileTree(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	branchStr := call.String("branch")
	if branchStr == "" {
		return ErrorOutput(call.ID, "branch is required")
	}
	branch, err := spi.NewBranchName(branchStr)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid branch: %v", err))
	}
	path := call.String("path")
	tree, err := p.client.GetFileTree(ctx, op, branch, path)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to get file tree: %v", err))
	}
	if len(tree) == 0 {
		return SuccessOutput(call.ID, "Empty directory or path not found.")
	}
	return SuccessOutput(call.ID, strings.Join(tree, "\n"))
}

func (p *SourceControlToolProvider) createBranch(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	branchName := call.String("branch_name")
	if branchName == "" {
		return ErrorOutput(call.ID, "branch_name is required")
	}
	target, err := spi.NewBranchName(branchName)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid branch_name: %v", err))
	}
	source, err := p.resolveBranch(call.String("from_branch"))
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid from_branch: %v", err))
	}
	if err := p.client.CreateBranch(ctx, op, source, target); err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to create branch: %v", err))
	}
	return SuccessOutput(call.ID, fmt.Sprintf("Branch '%s' created from '%s'.", target.Value(), source.Value()))
}

func (p *SourceControlToolProvider) commitFiles(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	branchName := call.String("branch_name")
	commitMsg := call.String("commit_message")
	if branchName == "" || commitMsg == "" {
		return ErrorOutput(call.ID, "branch_name and commit_message are required")
	}
	branch, err := spi.NewBranchName(branchName)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid branch_name: %v", err))
	}

	filesRaw, ok := call.Input["files"]
	if !ok {
		return ErrorOutput(call.ID, "files is required")
	}
	filesJSON, err := json.Marshal(filesRaw)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid files format: %v", err))
	}
	var files []spi.RepoFile
	if unmarshalErr := json.Unmarshal(filesJSON, &files); unmarshalErr != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid files format: %v", unmarshalErr))
	}
	if len(files) == 0 {
		return ErrorOutput(call.ID, "at least one file is required")
	}

	commitID, err := p.client.PushFiles(ctx, op, branch, files, commitMsg)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("commit failed: %v", err))
	}
	return SuccessOutput(call.ID, fmt.Sprintf("Committed %d file(s) to '%s'. Commit: %s", len(files), branch.Value(), commitID))
}

func (p *SourceControlToolProvider) createPR(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	title := call.String("title")
	sourceBranch := call.String("source_branch")
	if title == "" || sourceBranch == "" {
		return ErrorOutput(call.ID, "title and source_branch are required")
	}
	source, err := spi.NewBranchName(sourceBranch)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid source_branch: %v", err))
	}
	destStr := call.String("destination_branch")
	dest, err := p.resolveBranch(destStr)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid destination_branch: %v", err))
	}
	description := call.String("description")

	result, err := p.client.OpenPullRequest(ctx, op, title, description, source, dest)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to create PR: %v", err))
	}
	return SuccessOutput(call.ID, fmt.Sprintf("Pull request created: %s\nURL: %s", result.Title, result.URL))
}

func (p *SourceControlToolProvider) listPullRequests(call CallInput) Output {
	return ErrorOutput(call.ID, "list_pull_requests is not yet supported by the current source control provider")
}

func (p *SourceControlToolProvider) getCILogs(call CallInput) Output {
	runID := call.String("run_id")
	if runID == "" {
		return ErrorOutput(call.ID, "run_id is required")
	}
	return ErrorOutput(call.ID, "get_ci_logs is not yet supported by the current source control provider")
}

func (p *SourceControlToolProvider) viewFileOutline(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	filePath := call.String("file_path")
	if filePath == "" {
		return ErrorOutput(call.ID, "file_path is required")
	}
	branch, err := p.resolveBranch(call.String("branch"))
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid branch: %v", err))
	}
	content, err := p.client.GetFileContent(ctx, op, branch, filePath)
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to get file: %v", err))
	}
	ext := filepath.Ext(filePath)
	summarizer := appctx.NewFileSummarizer(appctx.DefaultThreshold)
	summary := summarizer.Summarize(content, ext)
	return SuccessOutput(call.ID, summary)
}

func (p *SourceControlToolProvider) searchGrep(ctx context.Context, op *spi.OperationContext, call CallInput) Output {
	query := call.String("query")
	if query == "" {
		return ErrorOutput(call.ID, "query is required")
	}
	branch, err := p.resolveBranch(call.String("branch"))
	if err != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("invalid branch: %v", err))
	}
	isRegex := call.Bool("is_regex", false)

	var re *regexp.Regexp
	if isRegex {
		re, err = regexp.Compile(query)
		if err != nil {
			return ErrorOutput(call.ID, fmt.Sprintf("invalid regex pattern: %v", err))
		}
	}

	filePath := call.String("file_path")
	if filePath != "" {
		// Search within a single file
		content, fileErr := p.client.GetFileContent(ctx, op, branch, filePath)
		if fileErr != nil {
			return ErrorOutput(call.ID, fmt.Sprintf("failed to get file: %v", fileErr))
		}
		matches := grepContent(filePath, content, query, re)
		if len(matches) == 0 {
			return SuccessOutput(call.ID, "No matches found.")
		}
		return SuccessOutput(call.ID, strings.Join(matches, "\n"))
	}

	// Whole-repo search: get file tree, filter, search each
	tree, treeErr := p.client.GetFileTree(ctx, op, branch, "")
	if treeErr != nil {
		return ErrorOutput(call.ID, fmt.Sprintf("failed to get file tree: %v", treeErr))
	}

	var allMatches []string
	const maxFiles = 200
	const maxTotalMatches = 500
	filesSearched := 0

	for _, path := range tree {
		if filesSearched >= maxFiles || len(allMatches) >= maxTotalMatches {
			break
		}
		if !isSearchableFile(path) {
			continue
		}
		content, fileErr := p.client.GetFileContent(ctx, op, branch, path)
		if fileErr != nil {
			continue
		}
		matches := grepContent(path, content, query, re)
		allMatches = append(allMatches, matches...)
		filesSearched++
	}

	if len(allMatches) == 0 {
		return SuccessOutput(call.ID, "No matches found.")
	}
	if len(allMatches) > maxTotalMatches {
		allMatches = allMatches[:maxTotalMatches]
		allMatches = append(allMatches, fmt.Sprintf("... truncated at %d matches", maxTotalMatches))
	}
	return SuccessOutput(call.ID, strings.Join(allMatches, "\n"))
}

// grepContent searches content line by line, returning matches with file path and line numbers.
func grepContent(filePath, content, query string, re *regexp.Regexp) []string {
	lines := strings.Split(content, "\n")
	var matches []string
	for i, line := range lines {
		var hit bool
		if re != nil {
			hit = re.MatchString(line)
		} else {
			hit = strings.Contains(line, query)
		}
		if hit {
			matches = append(matches, fmt.Sprintf("%s:%d: %s", filePath, i+1, line))
		}
	}
	return matches
}

// sourceExtensions contains file extensions considered searchable for grep.
var sourceExtensions = map[string]bool{
	".go": true, ".java": true, ".py": true, ".js": true, ".ts": true,
	".tsx": true, ".jsx": true, ".rb": true, ".rs": true, ".c": true,
	".cpp": true, ".h": true, ".hpp": true, ".cs": true, ".kt": true,
	".scala": true, ".swift": true, ".dart": true, ".sh": true,
	".yaml": true, ".yml": true, ".json": true, ".xml": true,
	".toml": true, ".md": true, ".txt": true, ".sql": true,
	".html": true, ".css": true, ".scss": true, ".less": true,
	".tf": true, ".hcl": true, ".gradle": true, ".properties": true,
}

// isSearchableFile returns true if the path looks like a source/text file.
func isSearchableFile(path string) bool {
	// Skip directories (ending with /)
	if strings.HasSuffix(path, "/") {
		return false
	}
	ext := strings.ToLower(filepath.Ext(path))
	return sourceExtensions[ext]
}
