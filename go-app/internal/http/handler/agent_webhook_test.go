package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
	"github.com/AirdropToTheMoon/ai-driven/internal/config"
)

type mockSQSSender struct {
	lastInput *sqs.SendMessageInput
	err       error
}

func (m *mockSQSSender) SendMessage(_ context.Context, params *sqs.SendMessageInput, _ ...func(*sqs.Options)) (*sqs.SendMessageOutput, error) {
	m.lastInput = params
	return &sqs.SendMessageOutput{}, m.err
}

func newTestHandler(sqsMock *mockSQSSender) *AgentWebhookHandler {
	classifier := agent.NewCommentIntentClassifier("ai")
	return NewAgentWebhookHandler(
		classifier,
		nil,
		sqsMock,
		"https://sqs.us-east-1.amazonaws.com/123456789/agent-queue.fifo",
		&config.CostConfig{
			MaxRequestsPerUserHour:   60,
			MaxRequestsPerTicketHour: 30,
		},
		&config.AgentConfig{
			MentionKeyword: "ai",
			BotAccountID:   "bot-account-id",
		},
	)
}

func TestHandleJira_ValidEvent(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"webhookEvent": "jira:issue_updated",
		"comment": map[string]any{
			"body": "@ai please review this ticket",
			"author": map[string]any{
				"displayName": "John Doe",
				"accountId":   "user-123",
			},
		},
		"issue": map[string]any{
			"key": "PROJ-123",
			"fields": map[string]any{
				"summary": "Test ticket",
			},
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/jira/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleJira(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusAccepted, rec.Code)

	var resp map[string]string
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Equal(t, "accepted", resp["status"])
	assert.Equal(t, "PROJ-123", resp["ticketKey"])
	assert.NotNil(t, sqsMock.lastInput)
}

func TestHandleJira_IrrelevantComment(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"webhookEvent": "jira:issue_updated",
		"comment": map[string]any{
			"body": "just a regular comment without mention",
			"author": map[string]any{
				"displayName": "John Doe",
				"accountId":   "user-123",
			},
		},
		"issue": map[string]any{
			"key": "PROJ-456",
			"fields": map[string]any{
				"summary": "Test ticket",
			},
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/jira/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleJira(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)

	var resp map[string]string
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Equal(t, "ignored", resp["status"])
	assert.Nil(t, sqsMock.lastInput)
}

func TestHandleJira_InvalidTicketKey(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"webhookEvent": "jira:issue_updated",
		"comment": map[string]any{
			"body": "@ai do something",
			"author": map[string]any{
				"displayName": "John Doe",
				"accountId":   "user-123",
			},
		},
		"issue": map[string]any{
			"key":    "invalid-key",
			"fields": map[string]any{},
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/jira/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleJira(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

func TestHandleJira_BotComment(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"webhookEvent": "jira:issue_updated",
		"comment": map[string]any{
			"body": "@ai some command",
			"author": map[string]any{
				"displayName": "AI Bot",
				"accountId":   "bot-account-id",
			},
		},
		"issue": map[string]any{
			"key": "PROJ-789",
			"fields": map[string]any{
				"summary": "Test ticket",
			},
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/jira/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleJira(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestHandleGitHub_ValidPRComment(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"action": "created",
		"comment": map[string]any{
			"body": "@ai please review",
			"user": map[string]any{
				"login": "developer",
			},
		},
		"pull_request": map[string]any{
			"title":  "PROJ-100 Add new feature",
			"number": 42,
			"head": map[string]any{
				"ref": "feature/PROJ-100-new-feature",
			},
		},
		"repository": map[string]any{
			"full_name": "org/repo",
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/github/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleGitHub(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusAccepted, rec.Code)

	var resp map[string]string
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Equal(t, "accepted", resp["status"])
	assert.Equal(t, "PROJ-100", resp["ticketKey"])
}

func TestHandleGitHub_NonCreatedAction(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"action": "edited",
		"comment": map[string]any{
			"body": "@ai please review",
			"user": map[string]any{
				"login": "developer",
			},
		},
		"pull_request": map[string]any{
			"title":  "PROJ-100 Add new feature",
			"number": 42,
			"head": map[string]any{
				"ref": "feature/PROJ-100",
			},
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/github/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleGitHub(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestHandleGitHub_NonPRIssueComment(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"action": "created",
		"comment": map[string]any{
			"body": "@ai help",
			"user": map[string]any{
				"login": "developer",
			},
		},
		"issue": map[string]any{
			"title":  "Bug report",
			"number": 10,
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/github/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleGitHub(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)

	var resp map[string]string
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Equal(t, "ignored", resp["status"])
}

func TestHandleGitHub_NoTicketKey(t *testing.T) {
	sqsMock := &mockSQSSender{}
	h := newTestHandler(sqsMock)

	payload := map[string]any{
		"action": "created",
		"comment": map[string]any{
			"body": "@ai review this",
			"user": map[string]any{
				"login": "developer",
			},
		},
		"pull_request": map[string]any{
			"title":  "no ticket key here",
			"number": 42,
			"head": map[string]any{
				"ref": "feature/no-ticket",
			},
		},
		"repository": map[string]any{
			"full_name": "org/repo",
		},
	}

	body, _ := json.Marshal(payload)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/github/agent", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.Set("rawBody", body)

	err := h.HandleGitHub(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)

	var resp map[string]string
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Equal(t, "ignored", resp["status"])
}

func TestExtractTicketKey(t *testing.T) {
	h := &AgentWebhookHandler{}

	tests := []struct {
		name     string
		payload  githubWebhookPayload
		expected string
	}{
		{
			name: "from PR title",
			payload: githubWebhookPayload{
				PullRequest: struct {
					Title  string `json:"title"`
					Number int    `json:"number"`
					Head   struct {
						Ref string `json:"ref"`
					} `json:"head"`
				}{
					Title: "PROJ-123 Fix bug",
				},
			},
			expected: "PROJ-123",
		},
		{
			name: "from branch name",
			payload: githubWebhookPayload{
				PullRequest: struct {
					Title  string `json:"title"`
					Number int    `json:"number"`
					Head   struct {
						Ref string `json:"ref"`
					} `json:"head"`
				}{
					Title: "no key here",
					Head: struct {
						Ref string `json:"ref"`
					}{
						Ref: "feature/TEAM-456-something",
					},
				},
			},
			expected: "TEAM-456",
		},
		{
			name: "from issue title",
			payload: githubWebhookPayload{
				Issue: struct {
					Title       string `json:"title"`
					Number      int    `json:"number"`
					PullRequest *struct {
						URL string `json:"url"`
					} `json:"pull_request,omitempty"`
				}{
					Title: "DEV-789 Some issue",
				},
			},
			expected: "DEV-789",
		},
		{
			name:     "no ticket key found",
			payload:  githubWebhookPayload{},
			expected: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := h.extractTicketKey(&tt.payload)
			assert.Equal(t, tt.expected, result)
		})
	}
}
