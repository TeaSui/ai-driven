package agent

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestToolUseResponse_HasToolUse(t *testing.T) {
	resp := &ToolUseResponse{StopReason: "tool_use"}
	assert.True(t, resp.HasToolUse())

	resp2 := &ToolUseResponse{StopReason: "end_turn"}
	assert.False(t, resp2.HasToolUse())
}

func TestToolUseResponse_TotalTokens(t *testing.T) {
	resp := &ToolUseResponse{InputTokens: 100, OutputTokens: 50}
	assert.Equal(t, 150, resp.TotalTokens())
}

func TestToolUseResponse_Text(t *testing.T) {
	blocks := []map[string]any{
		{"type": "text", "text": "Hello"},
		{"type": "tool_use", "name": "search", "id": "1"},
		{"type": "text", "text": "World"},
	}
	raw, _ := json.Marshal(blocks)

	resp := &ToolUseResponse{ContentBlocks: raw}
	assert.Equal(t, "Hello\nWorld", resp.Text())
}

func TestToolUseResponse_Text_EmptyBlocks(t *testing.T) {
	resp := &ToolUseResponse{ContentBlocks: json.RawMessage("[]")}
	assert.Equal(t, "", resp.Text())
}

func TestToolUseResponse_Text_InvalidJSON(t *testing.T) {
	resp := &ToolUseResponse{ContentBlocks: json.RawMessage("invalid")}
	assert.Equal(t, "", resp.Text())
}
