package claude

import (
	"context"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
)

// Ensure adapters implement agent.AiClient at compile time.
var (
	_ agent.AiClient = (*ClientAdapter)(nil)
	_ agent.AiClient = (*BedrockAdapter)(nil)
)

// ClientAdapter wraps Client to implement agent.AiClient.
type ClientAdapter struct {
	client *Client
}

// NewClientAdapter creates an AiClient backed by the Anthropic HTTP client.
func NewClientAdapter(client *Client) *ClientAdapter {
	return &ClientAdapter{client: client}
}

func (a *ClientAdapter) ChatWithTools(ctx context.Context, systemPrompt string,
	messages []map[string]any, tools []map[string]any,
) (*agent.ToolUseResponse, error) {
	resp, err := a.client.ChatWithTools(ctx, systemPrompt, messages, tools)
	if err != nil {
		return nil, err
	}
	return toAgentResponse(resp), nil
}

func (a *ClientAdapter) Chat(ctx context.Context, systemPrompt, userMessage string) (string, error) {
	return a.client.Chat(ctx, systemPrompt, userMessage)
}

func (a *ClientAdapter) Model() string {
	return a.client.Model()
}

func (a *ClientAdapter) WithModel(model string) agent.AiClient {
	return &ClientAdapter{client: a.client.WithModel(model)}
}

func (a *ClientAdapter) WithMaxTokens(maxTokens int) agent.AiClient {
	return &ClientAdapter{client: a.client.WithMaxTokens(maxTokens)}
}

func (a *ClientAdapter) WithTemperature(temp float64) agent.AiClient {
	return &ClientAdapter{client: a.client.WithTemperature(temp)}
}

// BedrockAdapter wraps BedrockClient to implement agent.AiClient.
type BedrockAdapter struct {
	client *BedrockClient
}

// NewBedrockAdapter creates an AiClient backed by AWS Bedrock.
func NewBedrockAdapter(client *BedrockClient) *BedrockAdapter {
	return &BedrockAdapter{client: client}
}

func (a *BedrockAdapter) ChatWithTools(ctx context.Context, systemPrompt string,
	messages []map[string]any, tools []map[string]any,
) (*agent.ToolUseResponse, error) {
	resp, err := a.client.ChatWithTools(ctx, systemPrompt, messages, tools)
	if err != nil {
		return nil, err
	}
	return toAgentResponse(resp), nil
}

func (a *BedrockAdapter) Chat(ctx context.Context, systemPrompt, userMessage string) (string, error) {
	return a.client.Chat(ctx, systemPrompt, userMessage)
}

func (a *BedrockAdapter) Model() string {
	return a.client.Model()
}

func (a *BedrockAdapter) WithModel(model string) agent.AiClient {
	return &BedrockAdapter{client: a.client.WithModel(model)}
}

func (a *BedrockAdapter) WithMaxTokens(maxTokens int) agent.AiClient {
	return &BedrockAdapter{client: a.client.WithMaxTokens(maxTokens)}
}

func (a *BedrockAdapter) WithTemperature(temp float64) agent.AiClient {
	return &BedrockAdapter{client: a.client.WithTemperature(temp)}
}

// toAgentResponse converts a claude.ToolUseResponse to agent.ToolUseResponse.
func toAgentResponse(r *ToolUseResponse) *agent.ToolUseResponse {
	return &agent.ToolUseResponse{
		ContentBlocks: r.ContentBlocks,
		StopReason:    r.StopReason,
		InputTokens:   r.InputTokens,
		OutputTokens:  r.OutputTokens,
	}
}
