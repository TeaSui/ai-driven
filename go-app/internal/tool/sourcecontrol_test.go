package tool

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- mock source control provider ---

type mockSourceControl struct {
	getFileContentFn func(ctx context.Context, op *spi.OperationContext, branch spi.BranchName, path string) (string, error)
	searchFilesFn    func(ctx context.Context, op *spi.OperationContext, query string) ([]string, error)
	getFileTreeFn    func(ctx context.Context, op *spi.OperationContext, branch spi.BranchName, path string) ([]string, error)
	createBranchFn   func(ctx context.Context, op *spi.OperationContext, source, target spi.BranchName) error
	pushFilesFn      func(ctx context.Context, op *spi.OperationContext, branch spi.BranchName, files []spi.RepoFile, commitMsg string) (string, error)
	openPRFn         func(ctx context.Context, op *spi.OperationContext, title, description string, source, target spi.BranchName) (*spi.PullRequestResult, error)
}

func (m *mockSourceControl) Name() string           { return "mock" }
func (m *mockSourceControl) Supports(_ string) bool { return true }
func (m *mockSourceControl) GetDefaultBranch(context.Context, *spi.OperationContext) (spi.BranchName, error) {
	return spi.NewBranchName("main")
}
func (m *mockSourceControl) GetFileContent(ctx context.Context, op *spi.OperationContext, branch spi.BranchName, path string) (string, error) {
	if m.getFileContentFn != nil {
		return m.getFileContentFn(ctx, op, branch, path)
	}
	return "file content", nil
}
func (m *mockSourceControl) SearchFiles(ctx context.Context, op *spi.OperationContext, query string) ([]string, error) {
	if m.searchFilesFn != nil {
		return m.searchFilesFn(ctx, op, query)
	}
	return []string{"file1.go", "file2.go"}, nil
}
func (m *mockSourceControl) GetFileTree(ctx context.Context, op *spi.OperationContext, branch spi.BranchName, path string) ([]string, error) {
	if m.getFileTreeFn != nil {
		return m.getFileTreeFn(ctx, op, branch, path)
	}
	return []string{"dir/", "file.go"}, nil
}
func (m *mockSourceControl) CreateBranch(ctx context.Context, op *spi.OperationContext, source, target spi.BranchName) error {
	if m.createBranchFn != nil {
		return m.createBranchFn(ctx, op, source, target)
	}
	return nil
}
func (m *mockSourceControl) PushFiles(ctx context.Context, op *spi.OperationContext, branch spi.BranchName, files []spi.RepoFile, commitMsg string) (string, error) {
	if m.pushFilesFn != nil {
		return m.pushFilesFn(ctx, op, branch, files, commitMsg)
	}
	return "abc123", nil
}
func (m *mockSourceControl) OpenPullRequest(ctx context.Context, op *spi.OperationContext, title, description string, source, target spi.BranchName) (*spi.PullRequestResult, error) {
	if m.openPRFn != nil {
		return m.openPRFn(ctx, op, title, description, source, target)
	}
	return &spi.PullRequestResult{ID: "1", URL: "https://example.com/pr/1", Title: title}, nil
}
func (m *mockSourceControl) AddPRComment(context.Context, *spi.OperationContext, string, string, string, string) error {
	return nil
}
func (m *mockSourceControl) AddPRCommentReply(context.Context, *spi.OperationContext, string, string, string, string, string) error {
	return nil
}

func newTestSCProvider() (*SourceControlToolProvider, *mockSourceControl) {
	mock := &mockSourceControl{}
	return NewSourceControlToolProvider(mock, "main"), mock
}

func testOp() *spi.OperationContext {
	op := spi.NewOperationContext("test-tenant")
	return &op
}

func TestSourceControlToolProvider_Namespace(t *testing.T) {
	p, _ := newTestSCProvider()
	assert.Equal(t, "source_control", p.Namespace())
}

func TestSourceControlToolProvider_Definitions(t *testing.T) {
	p, _ := newTestSCProvider()
	defs := p.Definitions()
	assert.Len(t, defs, 10)

	names := make(map[string]bool)
	for _, d := range defs {
		names[d.Name] = true
		assert.NotEmpty(t, d.Description)
		assert.NotNil(t, d.InputSchema)
	}
	expectedTools := []string{
		"source_control_get_file",
		"source_control_search_files",
		"source_control_get_file_tree",
		"source_control_create_branch",
		"source_control_commit_files",
		"source_control_create_pr",
		"source_control_list_pull_requests",
		"source_control_get_ci_logs",
		"source_control_view_file_outline",
		"source_control_search_grep",
	}
	for _, name := range expectedTools {
		assert.True(t, names[name], "missing tool definition: %s", name)
	}
}

func TestSourceControlToolProvider_GetFile(t *testing.T) {
	p, _ := newTestSCProvider()
	ctx := t.Context()
	op := testOp()

	out := p.Execute(ctx, op, CallInput{
		ID:    "t1",
		Name:  "source_control_get_file",
		Input: map[string]any{"file_path": "README.md"},
	})
	assert.False(t, out.IsError)
	assert.Equal(t, "file content", out.Content)
}

func TestSourceControlToolProvider_GetFile_MissingPath(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_get_file",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "file_path is required")
}

func TestSourceControlToolProvider_GetFile_Error(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileContentFn = func(context.Context, *spi.OperationContext, spi.BranchName, string) (string, error) {
		return "", errors.New("not found")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_get_file",
		Input: map[string]any{"file_path": "missing.txt"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "not found")
}

func TestSourceControlToolProvider_SearchFiles(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_files",
		Input: map[string]any{"query": "main"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "file1.go")
}

func TestSourceControlToolProvider_GetFileTree(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_get_file_tree",
		Input: map[string]any{"branch": "main"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "dir/")
}

func TestSourceControlToolProvider_CreateBranch(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_create_branch",
		Input: map[string]any{"branch_name": "feature/test"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "feature/test")
}

func TestSourceControlToolProvider_CommitFiles(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:   "t1",
		Name: "source_control_commit_files",
		Input: map[string]any{
			"branch_name":    "feature/test",
			"commit_message": "add file",
			"files": []any{
				map[string]any{"path": "test.go", "content": "package main", "operation": "add"},
			},
		},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "abc123")
}

func TestSourceControlToolProvider_CreatePR(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:   "t1",
		Name: "source_control_create_pr",
		Input: map[string]any{
			"title":         "Test PR",
			"source_branch": "feature/test",
		},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "https://example.com/pr/1")
}

func TestSourceControlToolProvider_Stubs(t *testing.T) {
	p, _ := newTestSCProvider()
	ctx := t.Context()
	op := testOp()

	t.Run("list_pull_requests", func(t *testing.T) {
		out := p.Execute(ctx, op, CallInput{ID: "t1", Name: "source_control_list_pull_requests", Input: map[string]any{}})
		assert.True(t, out.IsError)
		assert.Contains(t, out.Content, "not yet supported")
	})

	t.Run("get_ci_logs", func(t *testing.T) {
		out := p.Execute(ctx, op, CallInput{ID: "t1", Name: "source_control_get_ci_logs", Input: map[string]any{"run_id": "123"}})
		assert.True(t, out.IsError)
		assert.Contains(t, out.Content, "not yet supported")
	})

	t.Run("view_file_outline", func(t *testing.T) {
		out := p.Execute(ctx, op, CallInput{ID: "t1", Name: "source_control_view_file_outline", Input: map[string]any{"file_path": "main.go"}})
		assert.False(t, out.IsError)
		assert.Contains(t, out.Content, "file content")
	})

	t.Run("search_grep", func(t *testing.T) {
		out := p.Execute(ctx, op, CallInput{ID: "t1", Name: "source_control_search_grep", Input: map[string]any{"query": "func main"}})
		assert.False(t, out.IsError)
		assert.Contains(t, out.Content, "No matches found")
	})
}

func TestSourceControlToolProvider_UnknownTool(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_nonexistent",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "unknown tool")
}

func TestSourceControlToolProvider_ImplementsProvider(t *testing.T) {
	p, _ := newTestSCProvider()
	var _ Provider = p
	require.NotNil(t, p)
}

// --- view_file_outline tests ---

func TestViewFileOutline_MissingFilePath(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_view_file_outline",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "file_path is required")
}

func TestViewFileOutline_FileNotFound(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileContentFn = func(context.Context, *spi.OperationContext, spi.BranchName, string) (string, error) {
		return "", errors.New("file not found")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_view_file_outline",
		Input: map[string]any{"file_path": "missing.go"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "failed to get file")
}

func TestViewFileOutline_SmallFile_ReturnedUnchanged(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileContentFn = func(context.Context, *spi.OperationContext, spi.BranchName, string) (string, error) {
		return "package main\n\nfunc main() {}\n", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_view_file_outline",
		Input: map[string]any{"file_path": "main.go"},
	})
	assert.False(t, out.IsError)
	assert.Equal(t, "package main\n\nfunc main() {}\n", out.Content)
}

func TestViewFileOutline_LargeGoFile_Summarized(t *testing.T) {
	p, mock := newTestSCProvider()
	content := strings.Repeat("// padding comment line\n", 50) + `package service

import "context"

type Handler struct {
	svc Service
}

func NewHandler(svc Service) *Handler {
	return &Handler{svc: svc}
}

func (h *Handler) Handle(ctx context.Context) error {
	// lots of logic here
	return nil
}
`
	mock.getFileContentFn = func(context.Context, *spi.OperationContext, spi.BranchName, string) (string, error) {
		return content, nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_view_file_outline",
		Input: map[string]any{"file_path": "handler.go"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "SUMMARIZED")
	assert.Contains(t, out.Content, "package service")
	assert.Contains(t, out.Content, "func NewHandler")
}

func TestViewFileOutline_WithBranch(t *testing.T) {
	p, mock := newTestSCProvider()
	var calledBranch string
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, branch spi.BranchName, _ string) (string, error) {
		calledBranch = branch.Value()
		return "short", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_view_file_outline",
		Input: map[string]any{"file_path": "main.go", "branch": "develop"},
	})
	assert.False(t, out.IsError)
	assert.Equal(t, "develop", calledBranch)
}

// --- search_grep tests ---

func TestSearchGrep_MissingQuery(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "query is required")
}

func TestSearchGrep_SingleFile_PlainText(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) (string, error) {
		return "line one\nfunc main() {\n}\nline four", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "func main", "file_path": "main.go"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "main.go:2:")
	assert.Contains(t, out.Content, "func main()")
}

func TestSearchGrep_SingleFile_Regex(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) (string, error) {
		return "func foo() {}\nfunc bar() {}\nvar x = 1", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "func \\w+\\(\\)", "file_path": "main.go", "is_regex": true},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "main.go:1:")
	assert.Contains(t, out.Content, "main.go:2:")
	assert.NotContains(t, out.Content, "main.go:3:")
}

func TestSearchGrep_SingleFile_InvalidRegex(t *testing.T) {
	p, _ := newTestSCProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "[invalid", "file_path": "main.go", "is_regex": true},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "invalid regex")
}

func TestSearchGrep_SingleFile_NoMatches(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) (string, error) {
		return "line one\nline two", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "nonexistent", "file_path": "main.go"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "No matches found")
}

func TestSearchGrep_SingleFile_FileError(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) (string, error) {
		return "", errors.New("permission denied")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "test", "file_path": "secret.go"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "failed to get file")
}

func TestSearchGrep_WholeRepo(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileTreeFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) ([]string, error) {
		return []string{"main.go", "util.go", "README.md", "binary.png"}, nil
	}
	callCount := 0
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, path string) (string, error) {
		callCount++
		switch path {
		case "main.go":
			return "package main\nfunc main() {}\n", nil
		case "util.go":
			return "package util\nfunc helper() {}\n", nil
		case "README.md":
			return "# main project\nfunc is not real here\n", nil
		default:
			return "", errors.New("not found")
		}
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "func"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "main.go:2:")
	assert.Contains(t, out.Content, "util.go:2:")
	// binary.png should not be searched
	assert.NotContains(t, out.Content, "binary.png")
	// README.md should be searched (it's a searchable extension)
	assert.Contains(t, out.Content, "README.md:2:")
}

func TestSearchGrep_WholeRepo_TreeError(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileTreeFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) ([]string, error) {
		return nil, errors.New("access denied")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "test"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "failed to get file tree")
}

func TestSearchGrep_WholeRepo_NoMatches(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileTreeFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) ([]string, error) {
		return []string{"main.go"}, nil
	}
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) (string, error) {
		return "nothing here", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "zzzznotfound"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "No matches found")
}

func TestSearchGrep_WithBranch(t *testing.T) {
	p, mock := newTestSCProvider()
	var calledBranch string
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, branch spi.BranchName, _ string) (string, error) {
		calledBranch = branch.Value()
		return "match here", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "match", "file_path": "test.go", "branch": "develop"},
	})
	assert.False(t, out.IsError)
	assert.Equal(t, "develop", calledBranch)
}

func TestSearchGrep_SkipsDirectories(t *testing.T) {
	p, mock := newTestSCProvider()
	mock.getFileTreeFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, _ string) ([]string, error) {
		return []string{"dir/", "main.go"}, nil
	}
	mock.getFileContentFn = func(_ context.Context, _ *spi.OperationContext, _ spi.BranchName, path string) (string, error) {
		if path == "dir/" {
			return "", errors.New("is a directory")
		}
		return "func match() {}", nil
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "source_control_search_grep",
		Input: map[string]any{"query": "match"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "main.go:1:")
	assert.NotContains(t, out.Content, "dir/")
}
