package bitbucket

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/AirdropToTheMoon/ai-driven/internal/provider"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/rs/zerolog/log"
)

const defaultAPIBase = "https://api.bitbucket.org/2.0"

// Ensure Client implements SourceControlProvider.
var _ spi.SourceControlProvider = (*Client)(nil)

// Client is an HTTP client for the Bitbucket Cloud REST API 2.0.
type Client struct {
	workspace  string
	repoSlug   string
	authHeader string
	apiBase    string
	httpClient *http.Client
}

// NewClient creates a Bitbucket client with Basic Auth (app password).
func NewClient(workspace, repoSlug, username, appPassword string) *Client {
	cred := base64.StdEncoding.EncodeToString([]byte(username + ":" + appPassword))
	return &Client{
		workspace:  workspace,
		repoSlug:   repoSlug,
		authHeader: "Basic " + cred,
		apiBase:    defaultAPIBase,
		httpClient: &http.Client{Timeout: 60 * time.Second},
	}
}

// WithBaseURL overrides the API base URL (for testing).
func (c *Client) WithBaseURL(baseURL string) *Client {
	cp := *c
	cp.apiBase = strings.TrimRight(baseURL, "/")
	return &cp
}

// WithRepository returns a new client scoped to a different repo.
func (c *Client) WithRepository(workspace, repoSlug string) *Client {
	cp := *c
	cp.workspace = workspace
	cp.repoSlug = repoSlug
	return &cp
}

func (c *Client) Name() string { return "bitbucket" }

func (c *Client) Supports(repoURI string) bool {
	lower := strings.ToLower(repoURI)
	return strings.Contains(lower, "bitbucket.org") &&
		strings.Contains(repoURI, "/"+c.workspace+"/"+c.repoSlug)
}

func (c *Client) GetDefaultBranch(ctx context.Context, _ *spi.OperationContext) (spi.BranchName, error) {
	u := fmt.Sprintf("%s/repositories/%s/%s", c.apiBase, c.workspace, c.repoSlug)
	var resp struct {
		MainBranch struct {
			Name string `json:"name"`
		} `json:"mainbranch"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		return spi.BranchName{}, fmt.Errorf("get default branch: %w", err)
	}
	return spi.NewBranchName(resp.MainBranch.Name)
}

func (c *Client) CreateBranch(ctx context.Context, _ *spi.OperationContext, source, target spi.BranchName) error {
	// Get source branch commit hash
	branchURL := fmt.Sprintf("%s/repositories/%s/%s/refs/branches/%s",
		c.apiBase, c.workspace, c.repoSlug, url.PathEscape(source.Value()))
	var branchResp struct {
		Target struct {
			Hash string `json:"hash"`
		} `json:"target"`
	}
	if err := c.get(ctx, branchURL, &branchResp); err != nil {
		return fmt.Errorf("get source branch hash: %w", err)
	}

	// Create new branch
	createURL := fmt.Sprintf("%s/repositories/%s/%s/refs/branches", c.apiBase, c.workspace, c.repoSlug)
	body := map[string]any{
		"name":   target.Value(),
		"target": map[string]string{"hash": branchResp.Target.Hash},
	}
	return c.post(ctx, createURL, body, nil)
}

func (c *Client) GetFileTree(ctx context.Context, _ *spi.OperationContext, branch spi.BranchName, path string) ([]string, error) {
	p := path
	if p == "" {
		p = "/"
	}
	u := fmt.Sprintf("%s/repositories/%s/%s/src/%s/%s?pagelen=100",
		c.apiBase, c.workspace, c.repoSlug, url.PathEscape(branch.Value()), url.PathEscape(p))

	var resp struct {
		Values []struct {
			Path string `json:"path"`
			Type string `json:"type"`
		} `json:"values"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		var httpErr *provider.HTTPError
		if errors.As(err, &httpErr) && httpErr.IsNotFound() {
			return nil, nil
		}
		return nil, fmt.Errorf("get file tree: %w", err)
	}

	var files []string
	for _, v := range resp.Values {
		if v.Type == "commit_file" && v.Path != "" {
			files = append(files, v.Path)
		}
	}
	return files, nil
}

func (c *Client) GetFileContent(ctx context.Context, _ *spi.OperationContext, branch spi.BranchName, filePath string) (string, error) {
	u := fmt.Sprintf("%s/repositories/%s/%s/src/%s/%s",
		c.apiBase, c.workspace, c.repoSlug, url.PathEscape(branch.Value()), url.PathEscape(filePath))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		return "", err
	}
	c.setHeaders(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("get file content: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return "", nil
	}

	var buf bytes.Buffer
	if _, err := buf.ReadFrom(resp.Body); err != nil {
		return "", fmt.Errorf("read file content: %w", err)
	}
	return buf.String(), nil
}

func (c *Client) SearchFiles(ctx context.Context, _ *spi.OperationContext, query string) ([]string, error) {
	u := fmt.Sprintf("%s/repositories/%s/%s/src?q=%s",
		c.apiBase, c.workspace, c.repoSlug, url.QueryEscape(query))

	var resp struct {
		Values []struct {
			Path string `json:"path"`
		} `json:"values"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		log.Ctx(ctx).Warn().Err(err).Str("query", query).Msg("search files failed")
		return nil, nil
	}

	files := make([]string, 0, len(resp.Values))
	for _, v := range resp.Values {
		files = append(files, v.Path)
	}
	return files, nil
}

func (c *Client) PushFiles(ctx context.Context, _ *spi.OperationContext, branch spi.BranchName,
	files []spi.RepoFile, commitMsg string,
) (string, error) {
	u := fmt.Sprintf("%s/repositories/%s/%s/src", c.apiBase, c.workspace, c.repoSlug)

	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)

	if err := writer.WriteField("message", commitMsg); err != nil {
		return "", fmt.Errorf("write message field: %w", err)
	}
	if err := writer.WriteField("branch", branch.Value()); err != nil {
		return "", fmt.Errorf("write branch field: %w", err)
	}

	for _, f := range files {
		path := sanitizePath(f.Path)
		part, err := writer.CreateFormFile(path, path)
		if err != nil {
			return "", fmt.Errorf("create form file %s: %w", path, err)
		}
		if _, err := part.Write([]byte(f.Content)); err != nil {
			return "", fmt.Errorf("write file content %s: %w", path, err)
		}
	}

	if err := writer.Close(); err != nil {
		return "", fmt.Errorf("close multipart writer: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, &buf)
	if err != nil {
		return "", fmt.Errorf("create request: %w", err)
	}
	c.setHeaders(req)
	req.Header.Set("Content-Type", writer.FormDataContentType())

	if _, err := provider.DoJSON(c.httpClient, req, nil); err != nil {
		return "", fmt.Errorf("push files: %w", err)
	}
	return "success", nil
}

func (c *Client) OpenPullRequest(ctx context.Context, _ *spi.OperationContext,
	title, description string, source, target spi.BranchName,
) (*spi.PullRequestResult, error) {
	u := fmt.Sprintf("%s/repositories/%s/%s/pullrequests", c.apiBase, c.workspace, c.repoSlug)
	body := map[string]any{
		"title":       title,
		"description": description,
		"source":      map[string]any{"branch": map[string]string{"name": source.Value()}},
		"destination": map[string]any{"branch": map[string]string{"name": target.Value()}},
	}
	var resp struct {
		ID    json.Number `json:"id"`
		Links struct {
			HTML struct {
				Href string `json:"href"`
			} `json:"html"`
		} `json:"links"`
	}
	if err := c.post(ctx, u, body, &resp); err != nil {
		return nil, fmt.Errorf("create pull request: %w", err)
	}
	return &spi.PullRequestResult{
		ID:     resp.ID.String(),
		URL:    resp.Links.HTML.Href,
		Branch: source,
		Title:  title,
	}, nil
}

func (c *Client) AddPRComment(ctx context.Context, _ *spi.OperationContext, prID, _, comment, _ string) error {
	u := fmt.Sprintf("%s/repositories/%s/%s/pullrequests/%s/comments",
		c.apiBase, c.workspace, c.repoSlug, prID)
	body := map[string]any{
		"content": map[string]string{"raw": comment},
	}
	return c.post(ctx, u, body, nil)
}

func (c *Client) AddPRCommentReply(ctx context.Context, _ *spi.OperationContext, prID, _, comment, _, parentID string) error {
	u := fmt.Sprintf("%s/repositories/%s/%s/pullrequests/%s/comments",
		c.apiBase, c.workspace, c.repoSlug, prID)
	body := map[string]any{
		"content": map[string]string{"raw": comment},
		"parent":  map[string]string{"id": parentID},
	}
	return c.post(ctx, u, body, nil)
}

// --- private helpers ---

func (c *Client) get(ctx context.Context, u string, target any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		return err
	}
	c.setHeaders(req)
	_, err = provider.DoJSON(c.httpClient, req, target)
	return err
}

func (c *Client) post(ctx context.Context, u string, body, target any) error {
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal body: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(jsonBody))
	if err != nil {
		return err
	}
	c.setHeaders(req)
	req.Header.Set("Content-Type", "application/json")
	_, err = provider.DoJSON(c.httpClient, req, target)
	return err
}

func (c *Client) setHeaders(req *http.Request) {
	req.Header.Set("Authorization", c.authHeader)
	req.Header.Set("Accept", "application/json")
}

// sanitizePath cleans file paths for multipart upload.
func sanitizePath(path string) string {
	path = strings.TrimPrefix(path, "./")
	path = strings.TrimPrefix(path, ".")
	path = strings.ReplaceAll(path, "..", "")
	return path
}
