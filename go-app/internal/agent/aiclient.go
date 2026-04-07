package agent

import (
	"context"
	"encoding/json"
	"strings"
)

// AiClient is the platform-agnostic interface for AI model interactions.
type AiClient interface {
	ChatWithTools(ctx context.Context, systemPrompt string,
		messages []map[string]any, tools []map[string]any) (*ToolUseResponse, error)
	Chat(ctx context.Context, systemPrompt string, userMessage string) (string, error)
	Model() string
	WithModel(model string) AiClient
	WithMaxTokens(maxTokens int) AiClient
	WithTemperature(temp float64) AiClient
}

// ToolUseResponse holds the response from an AI chat call with tool support.
type ToolUseResponse struct {
	ContentBlocks json.RawMessage `json:"contentBlocks"`
	StopReason    string          `json:"stopReason"`
	InputTokens   int             `json:"inputTokens"`
	OutputTokens  int             `json:"outputTokens"`
}

func (r *ToolUseResponse) HasToolUse() bool {
	return r.StopReason == "tool_use"
}

func (r *ToolUseResponse) TotalTokens() int {
	return r.InputTokens + r.OutputTokens
}

// Text extracts and concatenates all text content blocks from the response.
func (r *ToolUseResponse) Text() string {
	var blocks []map[string]any
	if err := json.Unmarshal(r.ContentBlocks, &blocks); err != nil {
		return ""
	}

	var sb strings.Builder
	for _, block := range blocks {
		if blockType, ok := block["type"].(string); ok && blockType == "text" {
			if text, ok := block["text"].(string); ok {
				if sb.Len() > 0 {
					sb.WriteString("\n")
				}
				sb.WriteString(text)
			}
		}
	}
	return sb.String()
}
