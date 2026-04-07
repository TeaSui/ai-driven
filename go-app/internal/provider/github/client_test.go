package github

import (
	"encoding/json"
	"io"
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
	return NewClient("testowner", "testrepo", "ghp_test").WithBaseURL(server.URL)
}

func TestClient_Name(t *testing.T) {
	assert.Equal(t, "github", NewClient("o", "r", "t").Name())
}

func TestClient_Supports(t *testing.T) {
	c := NewClient("myorg", "myrepo", "token")
	assert.True(t, c.Supports("https://github.com/myorg/myrepo"))
	assert.True(t, c.Supports("https://github.com/myorg/myrepo.git"))
	assert.False(t, c.Supports("https://github.com/other/repo"))
	assert.False(t, c.Supports("https://bitbucket.org/myorg/myrepo"))
}

func TestClient_GetDefaultBranch(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/testowner/testrepo", func(w http.ResponseWriter, r *http.Request) {
		assert.Contains(t, r.Header.Get("Authorization"), "Bearer ")
		assert.Equal(t, apiAccept, r.Header.Get("Accept"))
		json.NewEncoder(w).Encode(map[string]string{"default_branch": "main"})
	})
	c := newTestClient(t, mux)

	branch, err := c.GetDefaultBranch(t.Context(), nil)
	require.NoError(t, err)
	assert.Equal(t, "main", branch.Value())
}

func TestClient_GetFileTree(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/testowner/testrepo/git/trees/main", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"tree": []map[string]string{
				{"path": "src/main.go", "type": "blob"},
				{"path": "src/", "type": "tree"},
				{"path": "README.md", "type": "blob"},
				{"path": "docs/guide.md", "type": "blob"},
			},
		})
	})
	c := newTestClient(t, mux)
	branch, _ := spi.NewBranchName("main")

	t.Run("all files", func(t *testing.T) {
		files, err := c.GetFileTree(t.Context(), nil, branch, "")
		require.NoError(t, err)
		assert.Len(t, files, 3) // Only blobs
		assert.Contains(t, files, "src/main.go")
		assert.Contains(t, files, "README.md")
	})

	t.Run("filtered by path", func(t *testing.T) {
		files, err := c.GetFileTree(t.Context(), nil, branch, "src/")
		require.NoError(t, err)
		assert.Len(t, files, 1)
		assert.Equal(t, "src/main.go", files[0])
	})
}

func TestClient_GetFileTree_NotFound(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})
	c := newTestClient(t, mux)
	branch, _ := spi.NewBranchName("main")

	files, err := c.GetFileTree(t.Context(), nil, branch, "")
	require.NoError(t, err)
	assert.Nil(t, files)
}

func TestClient_GetFileContent(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/testowner/testrepo/contents/src%2Fmain.go", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]string{
			"content":  "cGFja2FnZSBtYWlu", // base64 of "package main"
			"encoding": "base64",
		})
	})
	c := newTestClient(t, mux)
	branch, _ := spi.NewBranchName("main")

	content, err := c.GetFileContent(t.Context(), nil, branch, "src/main.go")
	require.NoError(t, err)
	assert.Equal(t, "package main", content)
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

func TestClient_SearchFiles(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/search/code", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"items": []map[string]string{
				{"path": "src/service.go"},
				{"path": "src/handler.go"},
			},
		})
	})
	c := newTestClient(t, mux)

	files, err := c.SearchFiles(t.Context(), nil, "func main")
	require.NoError(t, err)
	assert.Len(t, files, 2)
}

func TestClient_SearchFiles_GracefulDegradation(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	})
	c := newTestClient(t, mux)

	files, err := c.SearchFiles(t.Context(), nil, "query")
	require.NoError(t, err)
	assert.Nil(t, files) // Returns nil, not error
}

func TestClient_OpenPullRequest(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/testowner/testrepo/pulls", func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		var body map[string]string
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "Fix bug", body["title"])
		assert.Equal(t, "feature", body["head"])
		assert.Equal(t, "main", body["base"])

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]any{
			"number":   42,
			"html_url": "https://github.com/o/r/pull/42",
			"title":    "Fix bug",
			"head":     map[string]string{"ref": "feature"},
		})
	})
	c := newTestClient(t, mux)
	source, _ := spi.NewBranchName("feature")
	target, _ := spi.NewBranchName("main")

	pr, err := c.OpenPullRequest(t.Context(), nil, "Fix bug", "desc", source, target)
	require.NoError(t, err)
	assert.Equal(t, "42", pr.ID)
	assert.Equal(t, "https://github.com/o/r/pull/42", pr.URL)
	assert.Equal(t, "Fix bug", pr.Title)
}

func TestClient_AddPRComment(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/testowner/testrepo/issues/42/comments", func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		body, _ := io.ReadAll(r.Body)
		var req map[string]string
		require.NoError(t, json.Unmarshal(body, &req))
		assert.Equal(t, "Great work!", req["body"])
		json.NewEncoder(w).Encode(map[string]any{"id": 123})
	})
	c := newTestClient(t, mux)

	err := c.AddPRComment(t.Context(), nil, "42", "", "Great work!", "")
	require.NoError(t, err)
}

func TestClient_WithRepository(t *testing.T) {
	c := NewClient("orig", "repo1", "token")
	c2 := c.WithRepository("new", "repo2")
	assert.Equal(t, "orig", c.owner)
	assert.Equal(t, "new", c2.owner)
	assert.Equal(t, "repo2", c2.repo)
}

func TestClient_ListPullRequests(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/testowner/testrepo/pulls", func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.RawQuery, "state=open")
		json.NewEncoder(w).Encode([]map[string]any{
			{
				"number":   10,
				"title":    "Add feature",
				"state":    "open",
				"html_url": "https://github.com/o/r/pull/10",
				"head":     map[string]string{"ref": "feature-branch"},
				"base":     map[string]string{"ref": "main"},
				"user":     map[string]string{"login": "dev1"},
			},
			{
				"number":   11,
				"title":    "Fix typo",
				"state":    "open",
				"html_url": "https://github.com/o/r/pull/11",
				"head":     map[string]string{"ref": "typo-fix"},
				"base":     map[string]string{"ref": "main"},
				"user":     map[string]string{"login": "dev2"},
			},
		})
	})
	c := newTestClient(t, mux)

	prs, err := c.ListPullRequests(t.Context())
	require.NoError(t, err)
	assert.Len(t, prs, 2)
	assert.Equal(t, 10, prs[0].Number)
	assert.Equal(t, "Add feature", prs[0].Title)
	assert.Equal(t, "feature-branch", prs[0].Head)
	assert.Equal(t, "dev1", prs[0].User)
	assert.Equal(t, 11, prs[1].Number)
	assert.Equal(t, "Fix typo", prs[1].Title)
}

func TestClient_ListPullRequests_Empty(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/testowner/testrepo/pulls", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode([]map[string]any{})
	})
	c := newTestClient(t, mux)

	prs, err := c.ListPullRequests(t.Context())
	require.NoError(t, err)
	assert.Empty(t, prs)
}

func TestClient_InterfaceCompliance(t *testing.T) {
	var _ spi.SourceControlProvider = (*Client)(nil)
}
