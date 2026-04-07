package notification

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

func testApproval() *spi.PendingApprovalContext {
	return &spi.PendingApprovalContext{
		TicketKey:         "PROJ-123",
		ToolName:          "deploy_production",
		ActionDescription: "Deploy version 2.0 to production",
		GeneratedByModel:  "claude-opus-4",
		TriggerReason:     "Production deployment requires manual approval",
		TimeoutSeconds:    300,
	}
}

func TestBuildPayloadFormat(t *testing.T) {
	notifier := NewSlackNotifier("https://hooks.slack.com/test", "#approvals")
	approval := testApproval()

	payload, err := notifier.buildPayload(approval)
	require.NoError(t, err)

	var parsed slackPayload
	err = json.Unmarshal(payload, &parsed)
	require.NoError(t, err)

	assert.Equal(t, "#approvals", parsed.Channel)
	assert.Contains(t, parsed.Text, "PROJ-123")
	assert.Contains(t, parsed.Text, "Deploy version 2.0 to production")
	assert.Contains(t, parsed.Text, "Production deployment requires manual approval")
	assert.Contains(t, parsed.Text, "HIGH RISK ACTION")
}

func TestNotifyPendingSuccess(t *testing.T) {
	var receivedBody []byte

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		receivedBody = body

		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))
		assert.Equal(t, http.MethodPost, r.Method)

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()

	notifier := NewSlackNotifier(server.URL, "#test-channel")
	err := notifier.NotifyPending(t.Context(), testApproval())
	require.NoError(t, err)

	var parsed slackPayload
	err = json.Unmarshal(receivedBody, &parsed)
	require.NoError(t, err)
	assert.Equal(t, "#test-channel", parsed.Channel)
	assert.Contains(t, parsed.Text, "PROJ-123")
}

func TestNotifyPendingServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	notifier := NewSlackNotifier(server.URL, "#test-channel")
	err := notifier.NotifyPending(t.Context(), testApproval())
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "status 500")
}

func TestNotifyPendingNetworkError(t *testing.T) {
	notifier := NewSlackNotifier("http://localhost:1", "#test-channel")
	err := notifier.NotifyPending(t.Context(), testApproval())
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "failed to send Slack notification")
}
