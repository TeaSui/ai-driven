package claude

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
	"github.com/aws/aws-sdk-go-v2/service/bedrockruntime"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestClientAdapter_ImplementsAiClient(t *testing.T) {
	// Compile-time check is in adapter.go, but let's verify at runtime too
	var _ agent.AiClient = (*ClientAdapter)(nil)
	var _ agent.AiClient = (*BedrockAdapter)(nil)
}

func TestClientAdapter_ChatWithTools(t *testing.T) {
	client := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(MessagesResponse{
			Content:    []ContentBlock{{Type: "text", Text: "adapter test"}},
			StopReason: "end_turn",
			Usage:      Usage{InputTokens: 10, OutputTokens: 5},
		})
	})

	adapter := NewClientAdapter(client)
	resp, err := adapter.ChatWithTools(t.Context(), "sys", []map[string]any{
		{"role": "user", "content": "test"},
	}, nil)

	require.NoError(t, err)
	assert.Equal(t, "end_turn", resp.StopReason)
	assert.Equal(t, 10, resp.InputTokens)
	assert.Equal(t, 5, resp.OutputTokens)
	assert.Equal(t, "adapter test", resp.Text())
}

func TestClientAdapter_Chat(t *testing.T) {
	client := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(MessagesResponse{
			Content:    []ContentBlock{{Type: "text", Text: "hello"}},
			StopReason: "end_turn",
			Usage:      Usage{InputTokens: 5, OutputTokens: 2},
		})
	})

	adapter := NewClientAdapter(client)
	result, err := adapter.Chat(t.Context(), "sys", "hi")
	require.NoError(t, err)
	assert.Equal(t, "hello", result)
}

func TestClientAdapter_WithModel(t *testing.T) {
	client := NewClient("key", "model-a", 1000, 0.5)
	adapter := NewClientAdapter(client)

	modified := adapter.WithModel("model-b")
	assert.Equal(t, "model-a", adapter.Model())
	assert.Equal(t, "model-b", modified.Model())
}

func TestClientAdapter_WithMaxTokens(t *testing.T) {
	client := NewClient("key", "model", 1000, 0.5)
	var adapter agent.AiClient = NewClientAdapter(client)

	modified := adapter.WithMaxTokens(2000)
	assert.Equal(t, 1000, adapter.(*ClientAdapter).client.maxTokens)
	assert.Equal(t, 2000, modified.(*ClientAdapter).client.maxTokens)
}

func TestClientAdapter_WithTemperature(t *testing.T) {
	client := NewClient("key", "model", 1000, 0.5)
	var adapter agent.AiClient = NewClientAdapter(client)

	modified := adapter.WithTemperature(0.9)
	assert.Equal(t, 0.5, adapter.(*ClientAdapter).client.temperature)
	assert.Equal(t, 0.9, modified.(*ClientAdapter).client.temperature)
}

func TestBedrockAdapter_ChatWithTools(t *testing.T) {
	invoker := &mockInvoker{
		handler: func(_ context.Context, _ *bedrockruntime.InvokeModelInput) (*bedrockruntime.InvokeModelOutput, error) {
			resp := MessagesResponse{
				Content:    []ContentBlock{{Type: "text", Text: "bedrock adapter"}},
				StopReason: "end_turn",
				Usage:      Usage{InputTokens: 15, OutputTokens: 7},
			}
			body, _ := json.Marshal(resp)
			return &bedrockruntime.InvokeModelOutput{Body: body}, nil
		},
	}

	client := NewBedrockClient(invoker, "claude-sonnet-4-20250514", 4096, 0.3).
		WithRetryConfig(RetryConfig{MaxRetries: 0, InitialInterval: time.Millisecond, Multiplier: 1, MaxInterval: time.Millisecond})
	adapter := NewBedrockAdapter(client)

	resp, err := adapter.ChatWithTools(t.Context(), "sys", []map[string]any{
		{"role": "user", "content": "test"},
	}, nil)
	require.NoError(t, err)
	assert.Equal(t, "bedrock adapter", resp.Text())
}

func TestBedrockAdapter_WithModel(t *testing.T) {
	invoker := &mockInvoker{}
	client := NewBedrockClient(invoker, "model-a", 1000, 0.5)
	adapter := NewBedrockAdapter(client)

	modified := adapter.WithModel("model-b")
	assert.Equal(t, "model-a", adapter.Model())
	assert.Equal(t, "model-b", modified.Model())
}
