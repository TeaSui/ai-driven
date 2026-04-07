package tool

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestStringProp(t *testing.T) {
	p := StringProp("a file path")
	assert.Equal(t, "string", p["type"])
	assert.Equal(t, "a file path", p["description"])
}

func TestIntProp(t *testing.T) {
	p := IntProp("line count")
	assert.Equal(t, "integer", p["type"])
	assert.Equal(t, "line count", p["description"])
}

func TestBoolProp(t *testing.T) {
	p := BoolProp("enable flag")
	assert.Equal(t, "boolean", p["type"])
	assert.Equal(t, "enable flag", p["description"])
}

func TestArrayProp(t *testing.T) {
	p := ArrayProp("list of tags")
	assert.Equal(t, "array", p["type"])
	assert.Equal(t, "list of tags", p["description"])
	items, ok := p["items"].(map[string]any)
	require.True(t, ok)
	assert.Equal(t, "string", items["type"])
}

func TestSchemaBuilder_RequiredAndOptional(t *testing.T) {
	schema := ObjectSchema().
		Required("path", StringProp("file path")).
		Optional("line", IntProp("line number")).
		Required("force", BoolProp("force flag")).
		Build()

	assert.Equal(t, "object", schema["type"])

	props, ok := schema["properties"].(map[string]any)
	require.True(t, ok)
	assert.Len(t, props, 3)
	assert.NotNil(t, props["path"])
	assert.NotNil(t, props["line"])
	assert.NotNil(t, props["force"])

	req, ok := schema["required"].([]string)
	require.True(t, ok)
	assert.Equal(t, []string{"path", "force"}, req)
}

func TestSchemaBuilder_NoRequired(t *testing.T) {
	schema := ObjectSchema().
		Optional("verbose", BoolProp("verbose output")).
		Build()

	assert.Equal(t, "object", schema["type"])
	_, hasRequired := schema["required"]
	assert.False(t, hasRequired, "schema with no required fields should omit required key")

	props, ok := schema["properties"].(map[string]any)
	require.True(t, ok)
	assert.Len(t, props, 1)
}

func TestSchemaBuilder_Empty(t *testing.T) {
	schema := ObjectSchema().Build()

	assert.Equal(t, "object", schema["type"])
	props, ok := schema["properties"].(map[string]any)
	require.True(t, ok)
	assert.Empty(t, props)
}
