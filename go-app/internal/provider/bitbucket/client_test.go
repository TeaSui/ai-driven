package bitbucket

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestClient(t *testing.T, mux *http.ServeMux) *Client {
	t.Helper()
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return NewClient("testws", "testrepo", "user", "pass").WithBaseURL(server.URL)
}

func TestClient_Name(t *testing.T) {
	assert.Equal(t, "bitbucket", NewClient("w", "r", "u", "p").Name())
}

func TestClient_Supports(t *testing.T) {
	c := NewClient("myws", "myrepo", "u", "p")
	assert.True(t, c.Supports("https://bitbucket.org/myws/myrepo"))
	assert.False(t, c.Supports("https://github.com/myws/myrepo"))
	assert.False(t, c.Supports("https://bitbucket.org/other/repo"))
}

func TestClient_GetDefaultBranch(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repositories/testws/testrepo", func(w http.ResponseWriter, r *http.Request) {
		assert.Contains(t, r.Header.Get("Authorization"), "Basic ")
		json.NewEncoder(w).Encode(map[string]any{
			"mainbranch": map[string]string{"name": "develop"},
		})
	})
	c := newTestClient(t, mux)

	branch, err := c.GetDefaultBranch(t.Context(), nil)
	require.NoError(t, err)
	assert.Equal(t, "develop", branch.Value())
}

func TestClient_GetFileTree(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"values": []map[string]string{
				{"path": "src/main.go", "type": "commit_file"},
				{"path": "src/", "type": "commit_directory"},
				{"path": "README.md", "type": "commit_file"},
			},
		})
	})
	c := newTestClient(t, mux)
	branch, _ := spi.NewBranchName("main")

	files, err := c.GetFileTree(t.Context(), nil, branch, "")
	require.NoError(t, err)
	assert.Len(t, files, 2) // Only commit_file, no directories
}

func TestClient_GetFileContent(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte("package main\n\nfunc main() {}"))
	})
	c := newTestClient(t, mux)
	branch, _ := spi.NewBranchName("main")

	content, err := c.GetFileContent(t.Context(), nil, branch, "main.go")
	require.NoError(t, err)
	assert.Equal(t, "package main\n\nfunc main() {}", content)
}

func TestClient_GetFileContent_NotFound(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})
	c := newTestClient(t, mux)
	branch, _ := spi.NewBranchName("main")

	content, err := c.GetFileContent(t.Context(), nil, branch, "nope.go")
	require.NoError(t, err)
	assert.Empty(t, content)
}

func TestClient_OpenPullRequest(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repositories/testws/testrepo/pullrequests", func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "Fix it", body["title"])

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]any{
			"id": 7,
			"links": map[string]any{
				"html": map[string]string{"href": "https://bitbucket.org/testws/testrepo/pull-requests/7"},
			},
		})
	})
	c := newTestClient(t, mux)
	source, _ := spi.NewBranchName("feature")
	target, _ := spi.NewBranchName("main")

	pr, err := c.OpenPullRequest(t.Context(), nil, "Fix it", "desc", source, target)
	require.NoError(t, err)
	assert.Equal(t, "7", pr.ID)
	assert.Contains(t, pr.URL, "pull-requests/7")
}

func TestClient_AddPRComment(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repositories/testws/testrepo/pullrequests/5/comments", func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		content := body["content"].(map[string]any)
		assert.Equal(t, "LGTM", content["raw"])
		json.NewEncoder(w).Encode(map[string]string{"id": "comment-1"})
	})
	c := newTestClient(t, mux)

	err := c.AddPRComment(t.Context(), nil, "5", "", "LGTM", "")
	require.NoError(t, err)
}

func TestClient_WithRepository(t *testing.T) {
	c := NewClient("ws1", "repo1", "u", "p")
	c2 := c.WithRepository("ws2", "repo2")
	assert.Equal(t, "ws1", c.workspace)
	assert.Equal(t, "ws2", c2.workspace)
	assert.Equal(t, "repo2", c2.repoSlug)
}

func TestSanitizePath(t *testing.T) {
	tests := []struct {
		input, expected string
	}{
		{"./src/main.go", "src/main.go"},
		{".hidden", "hidden"},
		{"../etc/passwd", "./etc/passwd"},
		{"normal/path.go", "normal/path.go"},
	}
	for _, tt := range tests {
		assert.Equal(t, tt.expected, sanitizePath(tt.input))
	}
}

func TestClient_InterfaceCompliance(t *testing.T) {
	var _ spi.SourceControlProvider = (*Client)(nil)
}
