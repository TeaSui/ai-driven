package tool

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestTool_ToAPIFormat(t *testing.T) {
	tool := Tool{
		Name:        "source_control_get_file",
		Description: "Get file contents",
		InputSchema: map[string]any{
			"type": "object",
			"properties": map[string]any{
				"path": map[string]any{"type": "string"},
			},
		},
	}

	result := tool.ToAPIFormat()

	assert.Equal(t, "source_control_get_file", result["name"])
	assert.Equal(t, "Get file contents", result["description"])
	assert.NotNil(t, result["input_schema"])
}

func TestTool_ToAPIFormat_EmptySchema(t *testing.T) {
	tool := Tool{
		Name:        "simple_tool",
		Description: "A simple tool",
	}

	result := tool.ToAPIFormat()

	assert.Equal(t, "simple_tool", result["name"])
	assert.Nil(t, result["input_schema"])
}

func TestCall_InputString(t *testing.T) {
	tc := Call{
		ID:   "call-1",
		Name: "test_tool",
		Input: map[string]any{
			"path":  "/some/file",
			"count": 42,
		},
	}

	assert.Equal(t, "/some/file", tc.InputString("path"))
	assert.Equal(t, "", tc.InputString("missing"))
	assert.Equal(t, "", tc.InputString("count")) // wrong type
}

func TestCall_InputString_NilInput(t *testing.T) {
	tc := Call{ID: "call-1", Name: "test_tool"}
	assert.Equal(t, "", tc.InputString("anything"))
}

func TestCall_InputInt(t *testing.T) {
	tc := Call{
		ID:   "call-1",
		Name: "test_tool",
		Input: map[string]any{
			"count":   42,
			"float_v": float64(99),
			"int64_v": int64(7),
			"text":    "not a number",
		},
	}

	assert.Equal(t, 42, tc.InputInt("count", 0))
	assert.Equal(t, 99, tc.InputInt("float_v", 0))
	assert.Equal(t, 7, tc.InputInt("int64_v", 0))
	assert.Equal(t, -1, tc.InputInt("missing", -1))
	assert.Equal(t, -1, tc.InputInt("text", -1))
}

func TestCall_InputInt_NilInput(t *testing.T) {
	tc := Call{ID: "call-1", Name: "test_tool"}
	assert.Equal(t, 5, tc.InputInt("anything", 5))
}

func TestCall_InputBool(t *testing.T) {
	tc := Call{
		ID:   "call-1",
		Name: "test_tool",
		Input: map[string]any{
			"flag":   true,
			"off":    false,
			"number": 1,
		},
	}

	assert.True(t, tc.InputBool("flag", false))
	assert.False(t, tc.InputBool("off", true))
	assert.True(t, tc.InputBool("missing", true))
	assert.False(t, tc.InputBool("number", false)) // wrong type
}

func TestCall_InputBool_NilInput(t *testing.T) {
	tc := Call{ID: "call-1", Name: "test_tool"}
	assert.True(t, tc.InputBool("anything", true))
}

func TestSuccess(t *testing.T) {
	r := Success("id-1", "all good")
	assert.Equal(t, "id-1", r.ToolUseID)
	assert.Equal(t, "all good", r.Content)
	assert.False(t, r.IsError)
}

func TestSuccessf(t *testing.T) {
	r := Successf("id-1", "found %d items", 42)
	assert.Equal(t, "found 42 items", r.Content)
	assert.False(t, r.IsError)
}

func TestError(t *testing.T) {
	r := Error("id-2", "something broke")
	assert.Equal(t, "id-2", r.ToolUseID)
	assert.Equal(t, "something broke", r.Content)
	assert.True(t, r.IsError)
}

func TestErrorf(t *testing.T) {
	r := Errorf("id-2", "failed: %s", "timeout")
	assert.Equal(t, "failed: timeout", r.Content)
	assert.True(t, r.IsError)
}

func TestToolResult_ToContentBlock_Success(t *testing.T) {
	r := Success("id-1", "result data")
	block := r.ToContentBlock()

	assert.Equal(t, "tool_result", block["type"])
	assert.Equal(t, "id-1", block["tool_use_id"])
	assert.Equal(t, "result data", block["content"])
	_, hasIsError := block["is_error"]
	assert.False(t, hasIsError, "success result should not have is_error key")
}

func TestToolResult_ToContentBlock_Error(t *testing.T) {
	r := Error("id-2", "bad input")
	block := r.ToContentBlock()

	assert.Equal(t, "tool_result", block["type"])
	assert.Equal(t, "id-2", block["tool_use_id"])
	assert.Equal(t, "bad input", block["content"])
	require.Contains(t, block, "is_error")
	assert.True(t, block["is_error"].(bool))
}
