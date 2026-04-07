package github

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/AirdropToTheMoon/ai-driven/internal/provider"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/rs/zerolog/log"
)

const (
	defaultAPIBase = "https://api.github.com"
	apiAccept      = "application/vnd.github+json"
	mode100644     = "100644"
	blobType       = "blob"
	refsHeads      = "refs/heads/"
)

// Ensure Client implements SourceControlProvider.
var _ spi.SourceControlProvider = (*Client)(nil)

// Client is an HTTP client for the GitHub REST API v3.
type Client struct {
	owner      string
	repo       string
	token      string
	apiBase    string
	httpClient *http.Client
}

// NewClient creates a GitHub client with Bearer token auth.
func NewClient(owner, repo, token string) *Client {
	return &Client{
		owner:   owner,
		repo:    repo,
		token:   token,
		apiBase: defaultAPIBase,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

// WithRepository returns a new client scoped to a different repo.
func (c *Client) WithRepository(owner, repo string) *Client {
	cp := *c
	cp.owner = owner
	cp.repo = repo
	return &cp
}

// WithBaseURL overrides the API base URL (for testing).
func (c *Client) WithBaseURL(baseURL string) *Client {
	cp := *c
	cp.apiBase = strings.TrimRight(baseURL, "/")
	return &cp
}

func (c *Client) Name() string { return "github" }

func (c *Client) Supports(repoURI string) bool {
	lower := strings.ToLower(repoURI)
	return strings.Contains(lower, "github.com") &&
		strings.Contains(repoURI, "/"+c.owner+"/"+c.repo)
}

func (c *Client) GetDefaultBranch(ctx context.Context, _ *spi.OperationContext) (spi.BranchName, error) {
	var resp struct {
		DefaultBranch string `json:"default_branch"`
	}
	if err := c.get(ctx, c.repoURL(), &resp); err != nil {
		return spi.BranchName{}, fmt.Errorf("get default branch: %w", err)
	}
	return spi.NewBranchName(resp.DefaultBranch)
}

func (c *Client) CreateBranch(ctx context.Context, _ *spi.OperationContext, source, target spi.BranchName) error {
	sha, err := c.getBranchSHA(ctx, source.Value())
	if err != nil {
		return fmt.Errorf("get source branch SHA: %w", err)
	}

	body := map[string]string{
		"ref": refsHeads + target.Value(),
		"sha": sha,
	}
	return c.post(ctx, c.repoURL()+"/git/refs", body, nil)
}

func (c *Client) GetFileTree(ctx context.Context, _ *spi.OperationContext, branch spi.BranchName, path string) ([]string, error) {
	u := fmt.Sprintf("%s/git/trees/%s?recursive=1", c.repoURL(), url.PathEscape(branch.Value()))
	var resp struct {
		Tree []struct {
			Path string `json:"path"`
			Type string `json:"type"`
		} `json:"tree"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		var httpErr *provider.HTTPError
		if errors.As(err, &httpErr) && httpErr.IsNotFound() {
			return nil, nil
		}
		return nil, fmt.Errorf("get file tree: %w", err)
	}

	var files []string
	for _, entry := range resp.Tree {
		if entry.Type == blobType {
			if path == "" || strings.HasPrefix(entry.Path, path) {
				files = append(files, entry.Path)
			}
		}
	}
	return files, nil
}

func (c *Client) GetFileContent(ctx context.Context, _ *spi.OperationContext, branch spi.BranchName, filePath string) (string, error) {
	u := fmt.Sprintf("%s/contents/%s?ref=%s", c.repoURL(), url.PathEscape(filePath), url.QueryEscape(branch.Value()))
	var resp struct {
		Content  string `json:"content"`
		Encoding string `json:"encoding"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		var httpErr *provider.HTTPError
		if errors.As(err, &httpErr) && httpErr.IsNotFound() {
			return "", nil
		}
		return "", fmt.Errorf("get file content: %w", err)
	}

	if resp.Encoding == "base64" {
		decoded, err := base64.StdEncoding.DecodeString(strings.ReplaceAll(resp.Content, "\n", ""))
		if err != nil {
			return "", fmt.Errorf("decode base64 content: %w", err)
		}
		return string(decoded), nil
	}
	return resp.Content, nil
}

func (c *Client) SearchFiles(ctx context.Context, _ *spi.OperationContext, query string) ([]string, error) {
	u := fmt.Sprintf("%s/search/code?q=repo:%s/%s+%s", c.apiBase, c.owner, c.repo, url.QueryEscape(query))
	var resp struct {
		Items []struct {
			Path string `json:"path"`
		} `json:"items"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		log.Ctx(ctx).Warn().Err(err).Str("query", query).Msg("search files failed")
		return nil, nil
	}

	files := make([]string, 0, len(resp.Items))
	for _, item := range resp.Items {
		files = append(files, item.Path)
	}
	return files, nil
}

func (c *Client) PushFiles(ctx context.Context, _ *spi.OperationContext, branch spi.BranchName,
	files []spi.RepoFile, commitMsg string,
) (string, error) {
	branchSHA, err := c.getBranchSHA(ctx, branch.Value())
	if err != nil {
		return "", fmt.Errorf("get branch SHA: %w", err)
	}

	treeSHA, err := c.getCommitTreeSHA(ctx, branchSHA)
	if err != nil {
		return "", fmt.Errorf("get tree SHA: %w", err)
	}

	newTreeSHA, err := c.createTree(ctx, treeSHA, files)
	if err != nil {
		return "", fmt.Errorf("create tree: %w", err)
	}

	commitSHA, err := c.createCommit(ctx, commitMsg, newTreeSHA, branchSHA)
	if err != nil {
		return "", fmt.Errorf("create commit: %w", err)
	}

	if err := c.updateRef(ctx, branch.Value(), commitSHA); err != nil {
		return "", fmt.Errorf("update ref: %w", err)
	}
	return commitSHA, nil
}

func (c *Client) OpenPullRequest(ctx context.Context, _ *spi.OperationContext,
	title, description string, source, target spi.BranchName,
) (*spi.PullRequestResult, error) {
	body := map[string]string{
		"title": title,
		"body":  description,
		"head":  source.Value(),
		"base":  target.Value(),
	}
	var resp struct {
		Number int    `json:"number"`
		HTMLRL string `json:"html_url"`
		Title  string `json:"title"`
		Head   struct {
			Ref string `json:"ref"`
		} `json:"head"`
	}
	if err := c.post(ctx, c.repoURL()+"/pulls", body, &resp); err != nil {
		return nil, fmt.Errorf("create pull request: %w", err)
	}
	return &spi.PullRequestResult{
		ID:     fmt.Sprintf("%d", resp.Number),
		URL:    resp.HTMLRL,
		Branch: source,
		Title:  resp.Title,
	}, nil
}

func (c *Client) AddPRComment(ctx context.Context, _ *spi.OperationContext, prID, _, comment, _ string) error {
	u := fmt.Sprintf("%s/issues/%s/comments", c.repoURL(), prID)
	body := map[string]string{"body": comment}
	return c.post(ctx, u, body, nil)
}

func (c *Client) AddPRCommentReply(ctx context.Context, _ *spi.OperationContext, prID, _, comment, _, parentID string) error {
	// Try as PR review comment reply first, fall back to issue comment
	u := fmt.Sprintf("%s/issues/%s/comments", c.repoURL(), prID)
	body := map[string]string{"body": comment}
	if parentID != "" {
		log.Ctx(ctx).Debug().Str("parentID", parentID).Msg("adding reply (falling back to issue comment)")
	}
	return c.post(ctx, u, body, nil)
}

// PullRequestSummary holds a summary of a pull request for listing.
type PullRequestSummary struct {
	Number int    `json:"number"`
	Title  string `json:"title"`
	State  string `json:"state"`
	URL    string `json:"html_url"`
	Head   string `json:"head_branch"`
	Base   string `json:"base_branch"`
	User   string `json:"user"`
}

// ListPullRequests lists open pull requests for the repository.
func (c *Client) ListPullRequests(ctx context.Context) ([]PullRequestSummary, error) {
	u := fmt.Sprintf("%s/pulls?state=open&per_page=30", c.repoURL())
	var resp []struct {
		Number  int    `json:"number"`
		Title   string `json:"title"`
		State   string `json:"state"`
		HTMLURL string `json:"html_url"`
		Head    struct {
			Ref string `json:"ref"`
		} `json:"head"`
		Base struct {
			Ref string `json:"ref"`
		} `json:"base"`
		User struct {
			Login string `json:"login"`
		} `json:"user"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		return nil, fmt.Errorf("list pull requests: %w", err)
	}

	prs := make([]PullRequestSummary, 0, len(resp))
	for _, pr := range resp {
		prs = append(prs, PullRequestSummary{
			Number: pr.Number,
			Title:  pr.Title,
			State:  pr.State,
			URL:    pr.HTMLURL,
			Head:   pr.Head.Ref,
			Base:   pr.Base.Ref,
			User:   pr.User.Login,
		})
	}
	return prs, nil
}

// --- private helpers ---

func (c *Client) repoURL() string {
	return fmt.Sprintf("%s/repos/%s/%s", c.apiBase, c.owner, c.repo)
}

func (c *Client) getBranchSHA(ctx context.Context, branch string) (string, error) {
	u := fmt.Sprintf("%s/git/ref/heads/%s", c.repoURL(), url.PathEscape(branch))
	var resp struct {
		Object struct {
			SHA string `json:"sha"`
		} `json:"object"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		return "", err
	}
	return resp.Object.SHA, nil
}

func (c *Client) getCommitTreeSHA(ctx context.Context, commitSHA string) (string, error) {
	u := fmt.Sprintf("%s/git/commits/%s", c.repoURL(), commitSHA)
	var resp struct {
		Tree struct {
			SHA string `json:"sha"`
		} `json:"tree"`
	}
	if err := c.get(ctx, u, &resp); err != nil {
		return "", err
	}
	return resp.Tree.SHA, nil
}

func (c *Client) createTree(ctx context.Context, baseTreeSHA string, files []spi.RepoFile) (string, error) {
	treeEntries := make([]map[string]string, 0, len(files))
	for _, f := range files {
		treeEntries = append(treeEntries, map[string]string{
			"path":    f.Path,
			"mode":    mode100644,
			"type":    blobType,
			"content": f.Content,
		})
	}
	body := map[string]any{
		"base_tree": baseTreeSHA,
		"tree":      treeEntries,
	}
	var resp struct {
		SHA string `json:"sha"`
	}
	if err := c.post(ctx, c.repoURL()+"/git/trees", body, &resp); err != nil {
		return "", err
	}
	return resp.SHA, nil
}

func (c *Client) createCommit(ctx context.Context, msg, treeSHA, parentSHA string) (string, error) {
	body := map[string]any{
		"message": msg,
		"tree":    treeSHA,
		"parents": []string{parentSHA},
	}
	var resp struct {
		SHA string `json:"sha"`
	}
	if err := c.post(ctx, c.repoURL()+"/git/commits", body, &resp); err != nil {
		return "", err
	}
	return resp.SHA, nil
}

func (c *Client) updateRef(ctx context.Context, branch, sha string) error {
	u := fmt.Sprintf("%s/git/refs/heads/%s", c.repoURL(), url.PathEscape(branch))
	body := map[string]any{"sha": sha, "force": true}
	return c.patch(ctx, u, body)
}

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

func (c *Client) patch(ctx context.Context, u string, body any) error {
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal body: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPatch, u, bytes.NewReader(jsonBody))
	if err != nil {
		return err
	}
	c.setHeaders(req)
	req.Header.Set("Content-Type", "application/json")
	_, err = provider.DoJSON(c.httpClient, req, nil)
	return err
}

func (c *Client) setHeaders(req *http.Request) {
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Accept", apiAccept)
}
