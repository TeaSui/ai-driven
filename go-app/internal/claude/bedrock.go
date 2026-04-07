package claude

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/bedrockruntime"
	"github.com/rs/zerolog/log"
)

const bedrockAnthropicVersion = "bedrock-2023-05-31"

// BedrockInvoker is the interface for the Bedrock Runtime InvokeModel call.
type BedrockInvoker interface {
	InvokeModel(ctx context.Context, params *bedrockruntime.InvokeModelInput,
		optFns ...func(*bedrockruntime.Options)) (*bedrockruntime.InvokeModelOutput, error)
}

// BedrockClient wraps AWS Bedrock Runtime to call Claude models.
type BedrockClient struct {
	invoker     BedrockInvoker
	model       string
	maxTokens   int
	temperature float64
	retryCfg    RetryConfig
}

// NewBedrockClient creates a Bedrock-based Claude client.
func NewBedrockClient(invoker BedrockInvoker, model string, maxTokens int, temperature float64) *BedrockClient {
	return &BedrockClient{
		invoker:     invoker,
		model:       model,
		maxTokens:   maxTokens,
		temperature: temperature,
		retryCfg:    DefaultRetryConfig(),
	}
}

// WithModel returns a copy with a different model.
func (c *BedrockClient) WithModel(model string) *BedrockClient {
	cp := *c
	cp.model = model
	return &cp
}

// WithMaxTokens returns a copy with a different max tokens.
func (c *BedrockClient) WithMaxTokens(maxTokens int) *BedrockClient {
	cp := *c
	cp.maxTokens = maxTokens
	return &cp
}

// WithTemperature returns a copy with a different temperature.
func (c *BedrockClient) WithTemperature(temp float64) *BedrockClient {
	cp := *c
	cp.temperature = temp
	return &cp
}

// WithRetryConfig returns a copy with a different retry configuration.
func (c *BedrockClient) WithRetryConfig(cfg RetryConfig) *BedrockClient {
	cp := *c
	cp.retryCfg = cfg
	return &cp
}

// Model returns the current model name.
func (c *BedrockClient) Model() string {
	return c.model
}

// bedrockRequest is the Bedrock InvokeModel request body for Claude.
type bedrockRequest struct {
	AnthropicVersion string           `json:"anthropic_version"`
	MaxTokens        int              `json:"max_tokens"`
	Temperature      float64          `json:"temperature,omitempty"`
	System           json.RawMessage  `json:"system,omitempty"`
	Messages         []map[string]any `json:"messages"`
	Tools            []map[string]any `json:"tools,omitempty"`
}

// Chat sends a simple text chat without tools via Bedrock.
func (c *BedrockClient) Chat(ctx context.Context, systemPrompt, userMessage string) (string, error) {
	messages := []map[string]any{
		{"role": "user", "content": userMessage},
	}
	resp, err := c.ChatWithTools(ctx, systemPrompt, messages, nil)
	if err != nil {
		return "", err
	}
	return resp.Text(), nil
}

// ChatWithTools sends a chat request with tool definitions via Bedrock.
func (c *BedrockClient) ChatWithTools(ctx context.Context, systemPrompt string,
	messages []map[string]any, tools []map[string]any,
) (*ToolUseResponse, error) {
	return WithRetry(ctx, c.retryCfg, func() (*ToolUseResponse, error) {
		return c.doInvoke(ctx, systemPrompt, messages, tools)
	})
}

func (c *BedrockClient) doInvoke(ctx context.Context, systemPrompt string,
	messages []map[string]any, tools []map[string]any,
) (*ToolUseResponse, error) {
	req := bedrockRequest{
		AnthropicVersion: bedrockAnthropicVersion,
		MaxTokens:        c.maxTokens,
		Temperature:      c.temperature,
		Messages:         messages,
	}

	if systemPrompt != "" {
		blocks := []systemBlock{
			{
				Type: "text",
				Text: systemPrompt,
				CacheControl: &cacheControl{
					Type: "ephemeral",
				},
			},
		}
		sysJSON, err := json.Marshal(blocks)
		if err != nil {
			return nil, fmt.Errorf("marshal system prompt: %w", err)
		}
		req.System = sysJSON
	}

	if len(tools) > 0 {
		req.Tools = tools
	}

	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal bedrock request: %w", err)
	}

	modelID := toBedrockModelID(c.model)

	output, err := c.invoker.InvokeModel(ctx, &bedrockruntime.InvokeModelInput{
		ModelId:     aws.String(modelID),
		ContentType: aws.String("application/json"),
		Accept:      aws.String("application/json"),
		Body:        body,
	})
	if err != nil {
		return nil, fmt.Errorf("bedrock InvokeModel: %w", err)
	}

	parsed, err := ParseResponse(output.Body)
	if err != nil {
		return nil, fmt.Errorf("parse bedrock response: %w", err)
	}

	log.Ctx(ctx).Debug().
		Str("model", modelID).
		Str("stop_reason", parsed.StopReason).
		Int("input_tokens", parsed.Usage.InputTokens).
		Int("output_tokens", parsed.Usage.OutputTokens).
		Msg("Bedrock Claude response")

	return ToToolUseResponse(parsed)
}

// toBedrockModelID maps short model names to full Bedrock model IDs.
// If the name already looks like a full ARN or model ID, it's returned as-is.
func toBedrockModelID(model string) string {
	if strings.Contains(model, ":") || strings.HasPrefix(model, "anthropic.") {
		return model
	}

	// Map common short names to Bedrock model IDs
	modelMap := map[string]string{
		"claude-sonnet-4-20250514":   "anthropic.claude-sonnet-4-20250514-v1:0",
		"claude-sonnet-4-6":          "anthropic.claude-sonnet-4-6-v1:0",
		"claude-opus-4-20250514":     "anthropic.claude-opus-4-20250514-v1:0",
		"claude-opus-4-6":            "anthropic.claude-opus-4-6-v1:0",
		"claude-haiku-4-5-20251001":  "anthropic.claude-haiku-4-5-20251001-v1:0",
		"claude-3-5-sonnet-20241022": "anthropic.claude-3-5-sonnet-20241022-v2:0",
		"claude-3-5-haiku-20241022":  "anthropic.claude-3-5-haiku-20241022-v1:0",
	}

	if id, ok := modelMap[model]; ok {
		return id
	}

	// Fallback: prefix with anthropic.
	return "anthropic." + model + "-v1:0"
}
