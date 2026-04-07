package claude

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/bedrockruntime"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// mockInvoker implements BedrockInvoker for testing.
type mockInvoker struct {
	handler func(ctx context.Context, params *bedrockruntime.InvokeModelInput) (*bedrockruntime.InvokeModelOutput, error)
}

func (m *mockInvoker) InvokeModel(ctx context.Context, params *bedrockruntime.InvokeModelInput,
	_ ...func(*bedrockruntime.Options)) (*bedrockruntime.InvokeModelOutput, error) {
	return m.handler(ctx, params)
}

func TestBedrockClient_Chat_Success(t *testing.T) {
	invoker := &mockInvoker{
		handler: func(_ context.Context, params *bedrockruntime.InvokeModelInput) (*bedrockruntime.InvokeModelOutput, error) {
			assert.Equal(t, "anthropic.claude-sonnet-4-20250514-v1:0", *params.ModelId)
			assert.Equal(t, "application/json", *params.ContentType)

			var req bedrockRequest
			require.NoError(t, json.Unmarshal(params.Body, &req))
			assert.Equal(t, bedrockAnthropicVersion, req.AnthropicVersion)
			assert.Equal(t, 4096, req.MaxTokens)
			assert.Len(t, req.Messages, 1)

			// Verify system prompt has cache_control
			var sysBlocks []systemBlock
			require.NoError(t, json.Unmarshal(req.System, &sysBlocks))
			assert.Equal(t, "ephemeral", sysBlocks[0].CacheControl.Type)

			resp := MessagesResponse{
				ID:         "msg_br",
				Content:    []ContentBlock{{Type: "text", Text: "Hello from Bedrock!"}},
				StopReason: "end_turn",
				Usage:      Usage{InputTokens: 20, OutputTokens: 8},
			}
			body, _ := json.Marshal(resp)
			return &bedrockruntime.InvokeModelOutput{Body: body}, nil
		},
	}

	client := NewBedrockClient(invoker, "claude-sonnet-4-20250514", 4096, 0.3).
		WithRetryConfig(RetryConfig{
			MaxRetries:      0,
			InitialInterval: 1 * time.Millisecond,
			Multiplier:      1.0,
			MaxInterval:     1 * time.Millisecond,
		})

	result, err := client.Chat(t.Context(), "system prompt", "Hello")
	require.NoError(t, err)
	assert.Equal(t, "Hello from Bedrock!", result)
}

func TestBedrockClient_ChatWithTools(t *testing.T) {
	invoker := &mockInvoker{
		handler: func(_ context.Context, params *bedrockruntime.InvokeModelInput) (*bedrockruntime.InvokeModelOutput, error) {
			var req bedrockRequest
			require.NoError(t, json.Unmarshal(params.Body, &req))
			assert.Len(t, req.Tools, 1)

			resp := MessagesResponse{
				Content: []ContentBlock{
					{Type: "tool_use", ID: "toolu_1", Name: "search", Input: json.RawMessage(`{"q":"test"}`)},
				},
				StopReason: "tool_use",
				Usage:      Usage{InputTokens: 100, OutputTokens: 50},
			}
			body, _ := json.Marshal(resp)
			return &bedrockruntime.InvokeModelOutput{Body: body}, nil
		},
	}

	client := NewBedrockClient(invoker, "claude-sonnet-4-20250514", 4096, 0.3).
		WithRetryConfig(RetryConfig{MaxRetries: 0, InitialInterval: time.Millisecond, Multiplier: 1, MaxInterval: time.Millisecond})

	tools := []map[string]any{
		{"name": "search", "description": "Search", "input_schema": map[string]any{"type": "object"}},
	}
	messages := []map[string]any{
		{"role": "user", "content": "search for test"},
	}

	resp, err := client.ChatWithTools(t.Context(), "sys", messages, tools)
	require.NoError(t, err)
	assert.True(t, resp.HasToolUse())
	assert.Equal(t, 150, resp.TotalTokens())
}

func TestBedrockClient_Immutability(t *testing.T) {
	invoker := &mockInvoker{}
	original := NewBedrockClient(invoker, "model-a", 1000, 0.5)
	modified := original.WithModel("model-b").WithMaxTokens(2000).WithTemperature(0.9)

	assert.Equal(t, "model-a", original.Model())
	assert.Equal(t, "model-b", modified.Model())
	assert.Equal(t, 2000, modified.maxTokens)
}

func TestToBedrockModelID(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"claude-sonnet-4-20250514", "anthropic.claude-sonnet-4-20250514-v1:0"},
		{"claude-sonnet-4-6", "anthropic.claude-sonnet-4-6-v1:0"},
		{"claude-opus-4-20250514", "anthropic.claude-opus-4-20250514-v1:0"},
		{"claude-opus-4-6", "anthropic.claude-opus-4-6-v1:0"},
		{"claude-haiku-4-5-20251001", "anthropic.claude-haiku-4-5-20251001-v1:0"},
		{"claude-3-5-sonnet-20241022", "anthropic.claude-3-5-sonnet-20241022-v2:0"},
		{"claude-3-5-haiku-20241022", "anthropic.claude-3-5-haiku-20241022-v1:0"},
		// Already full ID
		{"anthropic.claude-sonnet-4-20250514-v1:0", "anthropic.claude-sonnet-4-20250514-v1:0"},
		// ARN-like
		{"arn:aws:bedrock:us-east-1::model/anthropic.claude", "arn:aws:bedrock:us-east-1::model/anthropic.claude"},
		// Unknown model falls back
		{"claude-future-model", "anthropic.claude-future-model-v1:0"},
	}
	for _, tt := range tests {
		t.Run(tt.input, func(t *testing.T) {
			assert.Equal(t, tt.expected, toBedrockModelID(tt.input))
		})
	}
}
