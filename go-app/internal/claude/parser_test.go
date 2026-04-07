package claude

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParseResponse_TextOnly(t *testing.T) {
	raw := `{
		"id": "msg_123",
		"type": "message",
		"role": "assistant",
		"content": [{"type": "text", "text": "Hello, world!"}],
		"model": "claude-sonnet-4-20250514",
		"stop_reason": "end_turn",
		"usage": {"input_tokens": 10, "output_tokens": 5}
	}`

	resp, err := ParseResponse([]byte(raw))
	require.NoError(t, err)
	assert.Equal(t, "msg_123", resp.ID)
	assert.Equal(t, "end_turn", resp.StopReason)
	assert.Equal(t, 10, resp.Usage.InputTokens)
	assert.Equal(t, 5, resp.Usage.OutputTokens)
	assert.Len(t, resp.Content, 1)
	assert.Equal(t, "text", resp.Content[0].Type)
	assert.Equal(t, "Hello, world!", resp.Content[0].Text)
}

func TestParseResponse_ToolUse(t *testing.T) {
	raw := `{
		"id": "msg_456",
		"type": "message",
		"role": "assistant",
		"content": [
			{"type": "text", "text": "I'll look that up."},
			{"type": "tool_use", "id": "toolu_1", "name": "get_ticket", "input": {"key": "PROJ-123"}}
		],
		"model": "claude-sonnet-4-20250514",
		"stop_reason": "tool_use",
		"usage": {"input_tokens": 100, "output_tokens": 50}
	}`

	resp, err := ParseResponse([]byte(raw))
	require.NoError(t, err)
	assert.Equal(t, "tool_use", resp.StopReason)
	assert.Len(t, resp.Content, 2)
	assert.Equal(t, "tool_use", resp.Content[1].Type)
	assert.Equal(t, "toolu_1", resp.Content[1].ID)
	assert.Equal(t, "get_ticket", resp.Content[1].Name)

	var input map[string]string
	require.NoError(t, json.Unmarshal(resp.Content[1].Input, &input))
	assert.Equal(t, "PROJ-123", input["key"])
}

func TestParseResponse_CacheMetrics(t *testing.T) {
	raw := `{
		"id": "msg_789",
		"type": "message",
		"role": "assistant",
		"content": [{"type": "text", "text": "cached"}],
		"model": "claude-sonnet-4-20250514",
		"stop_reason": "end_turn",
		"usage": {
			"input_tokens": 50,
			"output_tokens": 10,
			"cache_creation_input_tokens": 1000,
			"cache_read_input_tokens": 500
		}
	}`

	resp, err := ParseResponse([]byte(raw))
	require.NoError(t, err)
	assert.Equal(t, 1000, resp.Usage.CacheCreationInputTokens)
	assert.Equal(t, 500, resp.Usage.CacheReadInputTokens)
}

func TestToToolUseResponse(t *testing.T) {
	resp := &MessagesResponse{
		StopReason: "tool_use",
		Content: []ContentBlock{
			{Type: "text", Text: "thinking..."},
			{Type: "tool_use", ID: "toolu_1", Name: "search", Input: json.RawMessage(`{"q":"test"}`)},
		},
		Usage: Usage{
			InputTokens:              100,
			OutputTokens:             50,
			CacheCreationInputTokens: 200,
			CacheReadInputTokens:     300,
		},
	}

	result, err := ToToolUseResponse(resp)
	require.NoError(t, err)
	assert.True(t, result.HasToolUse())
	assert.Equal(t, 150, result.TotalTokens())
	assert.Equal(t, 200, result.CacheCreationInputTokens)
	assert.Equal(t, 300, result.CacheReadInputTokens)
	assert.Equal(t, "thinking...", result.Text())
}

func TestToolUseResponse_Text_MultipleBlocks(t *testing.T) {
	blocks := []ContentBlock{
		{Type: "text", Text: "first"},
		{Type: "tool_use", ID: "t1", Name: "x"},
		{Type: "text", Text: "second"},
	}
	data, _ := json.Marshal(blocks)
	resp := &ToolUseResponse{ContentBlocks: data, StopReason: "end_turn"}

	assert.Equal(t, "first\nsecond", resp.Text())
}

func TestToolUseResponse_Text_Empty(t *testing.T) {
	resp := &ToolUseResponse{ContentBlocks: json.RawMessage(`[]`)}
	assert.Equal(t, "", resp.Text())
}

func TestToolUseResponse_Text_InvalidJSON(t *testing.T) {
	resp := &ToolUseResponse{ContentBlocks: json.RawMessage(`not json`)}
	assert.Equal(t, "", resp.Text())
}

func TestParseResponse_InvalidJSON(t *testing.T) {
	_, err := ParseResponse([]byte(`not json`))
	assert.Error(t, err)
}
