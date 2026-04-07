package mcp

import (
	"testing"

	mcpgo "github.com/mark3labs/mcp-go/mcp"
	"github.com/stretchr/testify/assert"
)

func TestSanitizeToolName(t *testing.T) {
	tests := []struct {
		input, expected string
	}{
		{"get_file", "get_file"},
		{"search-code", "search_code"},
		{"list.repos", "list_repos"},
		{"my tool name", "my_tool_name"},
		{"__leading__", "leading"},
		{"normal", "normal"},
		{"a--b..c", "a_b_c"},
	}
	for _, tt := range tests {
		t.Run(tt.input, func(t *testing.T) {
			assert.Equal(t, tt.expected, sanitizeToolName(tt.input))
		})
	}
}

func TestConvertInputSchema(t *testing.T) {
	// Empty schema
	schema := convertInputSchema(mcpgo.ToolInputSchema{})
	assert.Equal(t, "object", schema["type"])
	assert.Nil(t, schema["properties"])
	assert.Nil(t, schema["required"])
}
