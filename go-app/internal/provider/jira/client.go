package jira

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/AirdropToTheMoon/ai-driven/internal/provider"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/rs/zerolog/log"
)

const httpTimeout = 60 * time.Second

// Ensure Client implements IssueTrackerProvider.
var _ spi.IssueTrackerProvider = (*Client)(nil)

// Client is an HTTP client for the Jira Cloud REST API v3.
type Client struct {
	baseURL    string
	authHeader string
	httpClient *http.Client
}

// NewClient creates a Jira client with Basic Auth.
func NewClient(baseURL, email, apiToken string) *Client {
	baseURL = strings.TrimRight(baseURL, "/")
	cred := base64.StdEncoding.EncodeToString([]byte(email + ":" + apiToken))
	return &Client{
		baseURL:    baseURL,
		authHeader: "Basic " + cred,
		httpClient: &http.Client{Timeout: httpTimeout},
	}
}

func (c *Client) Name() string { return "jira" }

// GetTicketDetails fetches the full Jira issue as a raw map.
func (c *Client) GetTicketDetails(ctx context.Context, _ *spi.OperationContext, ticketKey string) (map[string]any, error) {
	u := fmt.Sprintf("%s/rest/api/3/issue/%s", c.baseURL, url.PathEscape(ticketKey))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	c.setHeaders(req)

	var result map[string]any
	if _, err := provider.DoJSON(c.httpClient, req, &result); err != nil {
		return nil, fmt.Errorf("get ticket %s: %w", ticketKey, err)
	}
	return result, nil
}

// PostComment adds a comment to a Jira issue using Atlassian Document Format.
func (c *Client) PostComment(ctx context.Context, _ *spi.OperationContext, ticketKey, comment string) error {
	u := fmt.Sprintf("%s/rest/api/3/issue/%s/comment", c.baseURL, url.PathEscape(ticketKey))
	body := buildADFComment(comment)
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal comment: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(jsonBody))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	c.setHeaders(req)
	req.Header.Set("Content-Type", "application/json")

	var resp map[string]any
	if _, err := provider.DoJSON(c.httpClient, req, &resp); err != nil {
		return fmt.Errorf("add comment to %s: %w", ticketKey, err)
	}

	log.Ctx(ctx).Debug().Str("ticketKey", ticketKey).Msg("comment added to Jira issue")
	return nil
}

// UpdateLabels adds and/or removes labels on a Jira issue.
func (c *Client) UpdateLabels(ctx context.Context, _ *spi.OperationContext, ticketKey string, addLabels, removeLabels []string) error {
	u := fmt.Sprintf("%s/rest/api/3/issue/%s", c.baseURL, url.PathEscape(ticketKey))

	ops := make([]map[string]string, 0, len(addLabels)+len(removeLabels))
	for _, l := range addLabels {
		ops = append(ops, map[string]string{"add": l})
	}
	for _, l := range removeLabels {
		ops = append(ops, map[string]string{"remove": l})
	}

	body := map[string]any{
		"update": map[string]any{
			"labels": ops,
		},
	}
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal labels: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPut, u, bytes.NewReader(jsonBody))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	c.setHeaders(req)
	req.Header.Set("Content-Type", "application/json")

	if _, err := provider.DoJSON(c.httpClient, req, nil); err != nil {
		return fmt.Errorf("update labels on %s: %w", ticketKey, err)
	}
	return nil
}

// UpdateStatus transitions a Jira issue to the target status name.
func (c *Client) UpdateStatus(ctx context.Context, _ *spi.OperationContext, ticketKey, statusName string) error {
	transitions, err := c.getTransitions(ctx, ticketKey)
	if err != nil {
		return err
	}

	for _, t := range transitions {
		if strings.EqualFold(t.ToStatus, statusName) {
			return c.transitionTicket(ctx, ticketKey, t.ID)
		}
	}
	return fmt.Errorf("transition to '%s' not available for ticket %s", statusName, ticketKey)
}

// transition holds a Jira workflow transition.
type transition struct {
	ID       string
	ToStatus string
}

func (c *Client) getTransitions(ctx context.Context, ticketKey string) ([]transition, error) {
	u := fmt.Sprintf("%s/rest/api/3/issue/%s/transitions", c.baseURL, url.PathEscape(ticketKey))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	c.setHeaders(req)

	var resp struct {
		Transitions []struct {
			ID string `json:"id"`
			To struct {
				Name string `json:"name"`
			} `json:"to"`
		} `json:"transitions"`
	}
	if _, err := provider.DoJSON(c.httpClient, req, &resp); err != nil {
		return nil, fmt.Errorf("get transitions for %s: %w", ticketKey, err)
	}

	result := make([]transition, 0, len(resp.Transitions))
	for _, t := range resp.Transitions {
		result = append(result, transition{ID: t.ID, ToStatus: t.To.Name})
	}
	return result, nil
}

func (c *Client) transitionTicket(ctx context.Context, ticketKey, transitionID string) error {
	u := fmt.Sprintf("%s/rest/api/3/issue/%s/transitions", c.baseURL, url.PathEscape(ticketKey))
	body := map[string]any{
		"transition": map[string]any{"id": transitionID},
	}
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal transition: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(jsonBody))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	c.setHeaders(req)
	req.Header.Set("Content-Type", "application/json")

	if _, err := provider.DoJSON(c.httpClient, req, nil); err != nil {
		return fmt.Errorf("transition %s: %w", ticketKey, err)
	}

	log.Ctx(ctx).Debug().Str("ticketKey", ticketKey).Str("transitionId", transitionID).Msg("ticket transitioned")
	return nil
}

func (c *Client) setHeaders(req *http.Request) {
	req.Header.Set("Authorization", c.authHeader)
	req.Header.Set("Accept", "application/json")
}

// buildADFComment creates an Atlassian Document Format comment body.
func buildADFComment(text string) map[string]any {
	return map[string]any{
		"body": map[string]any{
			"type":    "doc",
			"version": 1,
			"content": []map[string]any{
				{
					"type": "paragraph",
					"content": []map[string]any{
						{"type": "text", "text": text},
					},
				},
			},
		},
	}
}
