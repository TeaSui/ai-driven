package spi

import "context"

// SourceControlProvider is the SPI for source control platforms (GitHub, Bitbucket).
type SourceControlProvider interface {
	Name() string
	Supports(repoURI string) bool
	GetDefaultBranch(ctx context.Context, op *OperationContext) (BranchName, error)
	CreateBranch(ctx context.Context, op *OperationContext, source, target BranchName) error
	PushFiles(ctx context.Context, op *OperationContext, branch BranchName, files []RepoFile, commitMsg string) (string, error)
	OpenPullRequest(ctx context.Context, op *OperationContext, title, description string, source, target BranchName) (*PullRequestResult, error)
	GetFileTree(ctx context.Context, op *OperationContext, branch BranchName, path string) ([]string, error)
	GetFileContent(ctx context.Context, op *OperationContext, branch BranchName, path string) (string, error)
	SearchFiles(ctx context.Context, op *OperationContext, query string) ([]string, error)
	AddPRComment(ctx context.Context, op *OperationContext, prID, filePath, comment, commitID string) error
	AddPRCommentReply(ctx context.Context, op *OperationContext, prID, filePath, comment, commitID, parentID string) error
}

// RepoFile represents a file to be committed.
type RepoFile struct {
	Path      string `json:"path"`
	Content   string `json:"content"`
	Operation string `json:"operation"` // "add", "modify", "delete"
}

// PullRequestResult holds the result of creating a pull request.
type PullRequestResult struct {
	ID     string     `json:"id"`
	URL    string     `json:"url"`
	Branch BranchName `json:"branch"`
	Title  string     `json:"title"`
}
