package claude

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestServer(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return NewClient("test-key", "claude-sonnet-4-20250514", 4096, 0.3).
		WithBaseURL(server.URL).
		WithRetryConfig(RetryConfig{
			MaxRetries:      0,
			InitialInterval: 1 * time.Millisecond,
			Multiplier:      1.0,
			MaxInterval:     1 * time.Millisecond,
		})
}

func TestClient_Chat_Success(t *testing.T) {
	client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Verify request
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, messagesPath, r.URL.Path)
		assert.Equal(t, "test-key", r.Header.Get("x-api-key"))
		assert.Equal(t, anthropicVersion, r.Header.Get("anthropic-version"))
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))

		body, _ := io.ReadAll(r.Body)
		var req messagesRequest
		require.NoError(t, json.Unmarshal(body, &req))
		assert.Equal(t, "claude-sonnet-4-20250514", req.Model)
		assert.Equal(t, 4096, req.MaxTokens)
		assert.Len(t, req.Messages, 1)

		// Verify system prompt has cache_control
		var sysBlocks []systemBlock
		require.NoError(t, json.Unmarshal(req.System, &sysBlocks))
		assert.Len(t, sysBlocks, 1)
		assert.Equal(t, "You are helpful.", sysBlocks[0].Text)
		assert.NotNil(t, sysBlocks[0].CacheControl)
		assert.Equal(t, "ephemeral", sysBlocks[0].CacheControl.Type)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(MessagesResponse{
			ID:         "msg_test",
			Type:       "message",
			Role:       "assistant",
			Content:    []ContentBlock{{Type: "text", Text: "Hi there!"}},
			StopReason: "end_turn",
			Usage:      Usage{InputTokens: 15, OutputTokens: 5},
		})
	})

	result, err := client.Chat(t.Context(), "You are helpful.", "Hello")
	require.NoError(t, err)
	assert.Equal(t, "Hi there!", result)
}

func TestClient_ChatWithTools_ToolUse(t *testing.T) {
	client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var req messagesRequest
		require.NoError(t, json.Unmarshal(body, &req))
		assert.Len(t, req.Tools, 1)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(MessagesResponse{
			ID:   "msg_tools",
			Type: "message",
			Role: "assistant",
			Content: []ContentBlock{
				{Type: "text", Text: "Let me check."},
				{Type: "tool_use", ID: "toolu_1", Name: "get_ticket", Input: json.RawMessage(`{"key":"PROJ-1"}`)},
			},
			StopReason: "tool_use",
			Usage:      Usage{InputTokens: 200, OutputTokens: 100, CacheReadInputTokens: 50},
		})
	})

	tools := []map[string]any{
		{"name": "get_ticket", "description": "Get a ticket", "input_schema": map[string]any{"type": "object"}},
	}
	messages := []map[string]any{
		{"role": "user", "content": "Get ticket PROJ-1"},
	}

	resp, err := client.ChatWithTools(t.Context(), "system", messages, tools)
	require.NoError(t, err)
	assert.True(t, resp.HasToolUse())
	assert.Equal(t, 300, resp.TotalTokens())
	assert.Equal(t, 50, resp.CacheReadInputTokens)
	assert.Equal(t, "Let me check.", resp.Text())
}

func TestClient_ChatWithTools_NoSystem(t *testing.T) {
	client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var req messagesRequest
		require.NoError(t, json.Unmarshal(body, &req))
		assert.Nil(t, req.System)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(MessagesResponse{
			ID:         "msg_nosys",
			Content:    []ContentBlock{{Type: "text", Text: "ok"}},
			StopReason: "end_turn",
			Usage:      Usage{InputTokens: 5, OutputTokens: 2},
		})
	})

	resp, err := client.ChatWithTools(t.Context(), "", []map[string]any{
		{"role": "user", "content": "hi"},
	}, nil)
	require.NoError(t, err)
	assert.Equal(t, "ok", resp.Text())
}

func TestClient_ErrorResponse(t *testing.T) {
	client := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]any{
			"error": map[string]any{
				"type":    "invalid_request_error",
				"message": "max_tokens must be positive",
			},
		})
	})

	_, err := client.Chat(t.Context(), "", "test")
	require.Error(t, err)
	apiErr, ok := AsAPIError(err)
	require.True(t, ok)
	assert.Equal(t, 400, apiErr.StatusCode)
	assert.Equal(t, "invalid_request_error", apiErr.Type)
	assert.Contains(t, apiErr.Message, "max_tokens")
}

func TestClient_ErrorResponse_NonJSON(t *testing.T) {
	client := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal server error"))
	})

	_, err := client.Chat(t.Context(), "", "test")
	require.Error(t, err)
	apiErr, ok := AsAPIError(err)
	require.True(t, ok)
	assert.Equal(t, 500, apiErr.StatusCode)
	assert.Contains(t, apiErr.Message, "internal server error")
}

func TestClient_Immutability(t *testing.T) {
	original := NewClient("key", "model-a", 1000, 0.5)

	modified := original.WithModel("model-b").WithMaxTokens(2000).WithTemperature(0.9)

	assert.Equal(t, "model-a", original.Model())
	assert.Equal(t, 1000, original.maxTokens)
	assert.Equal(t, 0.5, original.temperature)

	assert.Equal(t, "model-b", modified.Model())
	assert.Equal(t, 2000, modified.maxTokens)
	assert.Equal(t, 0.9, modified.temperature)
}
