package tool

import "fmt"

// Tool represents a tool definition exposed to the AI model.
type Tool struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	InputSchema map[string]any `json:"input_schema"`
}

// ToAPIFormat converts to Claude API format.
func (t Tool) ToAPIFormat() map[string]any {
	return map[string]any{
		"name":         t.Name,
		"description":  t.Description,
		"input_schema": t.InputSchema,
	}
}

// Call represents a tool invocation from the AI model.
type Call struct {
	ID    string         `json:"id"`
	Name  string         `json:"name"`
	Input map[string]any `json:"input"`
}

// InputString extracts a string field from input, returns "" if missing.
func (tc Call) InputString(key string) string {
	if tc.Input == nil {
		return ""
	}
	v, ok := tc.Input[key]
	if !ok {
		return ""
	}
	s, ok := v.(string)
	if !ok {
		return ""
	}
	return s
}

// InputInt extracts an int field from input, returns defaultVal if missing.
func (tc Call) InputInt(key string, defaultVal int) int {
	if tc.Input == nil {
		return defaultVal
	}
	v, ok := tc.Input[key]
	if !ok {
		return defaultVal
	}
	switch n := v.(type) {
	case int:
		return n
	case float64:
		return int(n)
	case int64:
		return int(n)
	default:
		return defaultVal
	}
}

// InputBool extracts a bool field from input, returns defaultVal if missing.
func (tc Call) InputBool(key string, defaultVal bool) bool {
	if tc.Input == nil {
		return defaultVal
	}
	v, ok := tc.Input[key]
	if !ok {
		return defaultVal
	}
	b, ok := v.(bool)
	if !ok {
		return defaultVal
	}
	return b
}

// Result represents the outcome of executing a tool.
type Result struct {
	ToolUseID string `json:"tool_use_id"`
	Content   string `json:"content"`
	IsError   bool   `json:"is_error,omitempty"`
}

// Success creates a successful Result.
func Success(toolUseID, content string) Result {
	return Result{
		ToolUseID: toolUseID,
		Content:   content,
		IsError:   false,
	}
}

// Successf creates a successful Result with formatted content.
func Successf(toolUseID, format string, args ...any) Result {
	return Success(toolUseID, fmt.Sprintf(format, args...))
}

// Error creates an error Result.
func Error(toolUseID, message string) Result {
	return Result{
		ToolUseID: toolUseID,
		Content:   message,
		IsError:   true,
	}
}

// Errorf creates an error Result with formatted message.
func Errorf(toolUseID, format string, args ...any) Result {
	return Error(toolUseID, fmt.Sprintf(format, args...))
}

// ToContentBlock converts to Claude tool_result content block.
func (r Result) ToContentBlock() map[string]any {
	block := map[string]any{
		"type":        "tool_result",
		"tool_use_id": r.ToolUseID,
		"content":     r.Content,
	}
	if r.IsError {
		block["is_error"] = true
	}
	return block
}
