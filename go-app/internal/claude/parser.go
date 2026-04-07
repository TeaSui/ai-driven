package claude

import "encoding/json"

// ContentBlock represents a single content block in a Claude response.
type ContentBlock struct {
	Type  string          `json:"type"`
	Text  string          `json:"text,omitempty"`
	ID    string          `json:"id,omitempty"`
	Name  string          `json:"name,omitempty"`
	Input json.RawMessage `json:"input,omitempty"`
}

// Usage holds token usage from a Claude API response, including cache metrics.
type Usage struct {
	InputTokens              int `json:"input_tokens"`
	OutputTokens             int `json:"output_tokens"`
	CacheCreationInputTokens int `json:"cache_creation_input_tokens,omitempty"`
	CacheReadInputTokens     int `json:"cache_read_input_tokens,omitempty"`
}

// MessagesResponse is the raw JSON shape of the Anthropic Messages API response.
type MessagesResponse struct {
	ID           string         `json:"id"`
	Type         string         `json:"type"`
	Role         string         `json:"role"`
	Content      []ContentBlock `json:"content"`
	Model        string         `json:"model"`
	StopReason   string         `json:"stop_reason"`
	StopSequence *string        `json:"stop_sequence,omitempty"`
	Usage        Usage          `json:"usage"`
}

// ParseResponse parses raw JSON from the Anthropic Messages API into a MessagesResponse.
func ParseResponse(data []byte) (*MessagesResponse, error) {
	var resp MessagesResponse
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ToToolUseResponse converts a parsed MessagesResponse to the agent-layer ToolUseResponse.
func ToToolUseResponse(resp *MessagesResponse) (*ToolUseResponse, error) {
	blocks, err := json.Marshal(resp.Content)
	if err != nil {
		return nil, err
	}
	return &ToolUseResponse{
		ContentBlocks:            blocks,
		StopReason:               resp.StopReason,
		InputTokens:              resp.Usage.InputTokens,
		OutputTokens:             resp.Usage.OutputTokens,
		CacheCreationInputTokens: resp.Usage.CacheCreationInputTokens,
		CacheReadInputTokens:     resp.Usage.CacheReadInputTokens,
	}, nil
}

// ToolUseResponse is the Claude client's response type, mirroring agent.ToolUseResponse
// but adding cache metrics. The agent layer can convert as needed.
type ToolUseResponse struct {
	ContentBlocks            json.RawMessage `json:"contentBlocks"`
	StopReason               string          `json:"stopReason"`
	InputTokens              int             `json:"inputTokens"`
	OutputTokens             int             `json:"outputTokens"`
	CacheCreationInputTokens int             `json:"cacheCreationInputTokens,omitempty"`
	CacheReadInputTokens     int             `json:"cacheReadInputTokens,omitempty"`
}

// HasToolUse returns true if the response ended because the model wants to use a tool.
func (r *ToolUseResponse) HasToolUse() bool {
	return r.StopReason == "tool_use"
}

// TotalTokens returns the sum of input and output tokens.
func (r *ToolUseResponse) TotalTokens() int {
	return r.InputTokens + r.OutputTokens
}

// Text extracts and concatenates all text content blocks from the response.
func (r *ToolUseResponse) Text() string {
	var blocks []ContentBlock
	if err := json.Unmarshal(r.ContentBlocks, &blocks); err != nil {
		return ""
	}
	var result string
	for _, block := range blocks {
		if block.Type == "text" {
			if result != "" {
				result += "\n"
			}
			result += block.Text
		}
	}
	return result
}
