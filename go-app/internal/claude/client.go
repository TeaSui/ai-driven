package claude

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/rs/zerolog/log"
)

const (
	defaultBaseURL        = "https://api.anthropic.com"
	messagesPath          = "/v1/messages"
	anthropicVersion      = "2023-06-01"
	defaultRequestTimeout = 5 * time.Minute
)

// Client is a direct HTTP client for the Anthropic Messages API.
type Client struct {
	apiKey      string
	model       string
	maxTokens   int
	temperature float64
	baseURL     string
	httpClient  *http.Client
	retryCfg    RetryConfig
}

// NewClient creates a new Anthropic API client.
func NewClient(apiKey, model string, maxTokens int, temperature float64) *Client {
	return &Client{
		apiKey:      apiKey,
		model:       model,
		maxTokens:   maxTokens,
		temperature: temperature,
		baseURL:     defaultBaseURL,
		httpClient: &http.Client{
			Timeout: defaultRequestTimeout,
		},
		retryCfg: DefaultRetryConfig(),
	}
}

// WithBaseURL overrides the API base URL (useful for testing).
func (c *Client) WithBaseURL(url string) *Client {
	cp := *c
	cp.baseURL = url
	return &cp
}

// WithHTTPClient overrides the HTTP client (useful for testing).
func (c *Client) WithHTTPClient(hc *http.Client) *Client {
	cp := *c
	cp.httpClient = hc
	return &cp
}

// WithModel returns a copy with a different model.
func (c *Client) WithModel(model string) *Client {
	cp := *c
	cp.model = model
	return &cp
}

// WithMaxTokens returns a copy with a different max tokens.
func (c *Client) WithMaxTokens(maxTokens int) *Client {
	cp := *c
	cp.maxTokens = maxTokens
	return &cp
}

// WithTemperature returns a copy with a different temperature.
func (c *Client) WithTemperature(temp float64) *Client {
	cp := *c
	cp.temperature = temp
	return &cp
}

// WithRetryConfig returns a copy with a different retry configuration.
func (c *Client) WithRetryConfig(cfg RetryConfig) *Client {
	cp := *c
	cp.retryCfg = cfg
	return &cp
}

// Model returns the current model name.
func (c *Client) Model() string {
	return c.model
}

// messagesRequest is the request body for the Anthropic Messages API.
type messagesRequest struct {
	Model       string           `json:"model"`
	MaxTokens   int              `json:"max_tokens"`
	Temperature float64          `json:"temperature,omitempty"`
	System      json.RawMessage  `json:"system,omitempty"`
	Messages    []map[string]any `json:"messages"`
	Tools       []map[string]any `json:"tools,omitempty"`
}

// cacheControl is the cache_control block for prompt caching.
type cacheControl struct {
	Type string `json:"type"`
}

// systemBlock is a system prompt content block with optional cache control.
type systemBlock struct {
	Type         string        `json:"type"`
	Text         string        `json:"text"`
	CacheControl *cacheControl `json:"cache_control,omitempty"`
}

// Chat sends a simple text chat without tools.
func (c *Client) Chat(ctx context.Context, systemPrompt, userMessage string) (string, error) {
	messages := []map[string]any{
		{"role": "user", "content": userMessage},
	}
	resp, err := c.ChatWithTools(ctx, systemPrompt, messages, nil)
	if err != nil {
		return "", err
	}
	return resp.Text(), nil
}

// ChatWithTools sends a chat request with tool definitions and returns the full response.
// The system prompt is sent with cache_control: ephemeral for prompt caching.
func (c *Client) ChatWithTools(ctx context.Context, systemPrompt string,
	messages []map[string]any, tools []map[string]any,
) (*ToolUseResponse, error) {
	return WithRetry(ctx, c.retryCfg, func() (*ToolUseResponse, error) {
		return c.doRequest(ctx, systemPrompt, messages, tools)
	})
}

func (c *Client) doRequest(ctx context.Context, systemPrompt string,
	messages []map[string]any, tools []map[string]any,
) (*ToolUseResponse, error) {
	req := messagesRequest{
		Model:       c.model,
		MaxTokens:   c.maxTokens,
		Temperature: c.temperature,
		Messages:    messages,
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
		return nil, fmt.Errorf("marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+messagesPath, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("x-api-key", c.apiKey)
	httpReq.Header.Set("anthropic-version", anthropicVersion)

	httpResp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("execute request: %w", err)
	}
	defer httpResp.Body.Close()

	respBody, err := io.ReadAll(httpResp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	if httpResp.StatusCode != http.StatusOK {
		return nil, parseErrorResponse(httpResp.StatusCode, respBody)
	}

	parsed, err := ParseResponse(respBody)
	if err != nil {
		return nil, fmt.Errorf("parse response: %w", err)
	}

	log.Ctx(ctx).Debug().
		Str("model", parsed.Model).
		Str("stop_reason", parsed.StopReason).
		Int("input_tokens", parsed.Usage.InputTokens).
		Int("output_tokens", parsed.Usage.OutputTokens).
		Int("cache_creation_tokens", parsed.Usage.CacheCreationInputTokens).
		Int("cache_read_tokens", parsed.Usage.CacheReadInputTokens).
		Msg("Claude API response")

	return ToToolUseResponse(parsed)
}

// parseErrorResponse creates an APIError from a non-200 response.
func parseErrorResponse(statusCode int, body []byte) *APIError {
	var errResp struct {
		Error struct {
			Type    string `json:"type"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &errResp); err != nil {
		return &APIError{
			StatusCode: statusCode,
			Message:    string(body),
		}
	}
	return &APIError{
		StatusCode: statusCode,
		Type:       errResp.Error.Type,
		Message:    errResp.Error.Message,
	}
}
