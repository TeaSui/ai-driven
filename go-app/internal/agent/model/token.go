package model

// TokenUsage tracks token consumption for an AI interaction.
type TokenUsage struct {
	InputTokens  int `json:"inputTokens"`
	OutputTokens int `json:"outputTokens"`
	TotalTokens  int `json:"totalTokens"`
}

func (t *TokenUsage) Add(other TokenUsage) {
	t.InputTokens += other.InputTokens
	t.OutputTokens += other.OutputTokens
	t.TotalTokens += other.TotalTokens
}

func NewTokenUsage(input, output int) TokenUsage {
	return TokenUsage{
		InputTokens:  input,
		OutputTokens: output,
		TotalTokens:  input + output,
	}
}
