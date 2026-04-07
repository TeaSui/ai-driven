package spi

import "context"

// AiProvider is the SPI for AI chat model backends (Claude, Bedrock, etc.).
type AiProvider interface {
	Name() string
	Chat(ctx context.Context, op OperationContext, systemPrompt string,
		messages []map[string]any, tools []map[string]any) (*ChatResponse, error)
}

// ChatResponse holds the result of an AI chat call.
type ChatResponse struct {
	Text         string
	ToolCalls    []ToolCallData
	InputTokens  int
	OutputTokens int
}

// ToolCallData represents a single tool call from the AI model.
type ToolCallData struct {
	ID    string
	Name  string
	Input map[string]any
}
