package tool

import (
	"context"
	"fmt"
	"strconv"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// Def represents a tool definition exposed to the AI model.
type Def struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	InputSchema map[string]any `json:"input_schema"`
}

// CallInput represents a tool call from the AI model.
type CallInput struct {
	ID    string
	Name  string
	Input map[string]any
}

// String returns the string value for the given key, or empty string if missing.
func (tc CallInput) String(key string) string {
	v, ok := tc.Input[key]
	if !ok {
		return ""
	}
	s, ok := v.(string)
	if !ok {
		return fmt.Sprintf("%v", v)
	}
	return s
}

// Int returns the int value for the given key, or the default if missing or not parseable.
func (tc CallInput) Int(key string, def int) int {
	v, ok := tc.Input[key]
	if !ok {
		return def
	}
	switch val := v.(type) {
	case float64:
		return int(val)
	case int:
		return val
	case string:
		n, err := strconv.Atoi(val)
		if err != nil {
			return def
		}
		return n
	default:
		return def
	}
}

// Bool returns the bool value for the given key, or the default if missing.
func (tc CallInput) Bool(key string, def bool) bool {
	v, ok := tc.Input[key]
	if !ok {
		return def
	}
	switch val := v.(type) {
	case bool:
		return val
	case string:
		b, err := strconv.ParseBool(val)
		if err != nil {
			return def
		}
		return b
	default:
		return def
	}
}

// Output represents the result of executing a tool.
type Output struct {
	ToolUseID string
	Content   string
	IsError   bool
}

// SuccessOutput creates a successful tool output.
func SuccessOutput(id, content string) Output {
	return Output{ToolUseID: id, Content: content, IsError: false}
}

// ErrorOutput creates an error tool output.
func ErrorOutput(id, msg string) Output {
	return Output{ToolUseID: id, Content: msg, IsError: true}
}

// Provider defines the interface for tool providers.
type Provider interface {
	Namespace() string
	Definitions() []Def
	Execute(ctx context.Context, op *spi.OperationContext, call CallInput) Output
	MaxOutputChars() int
}
