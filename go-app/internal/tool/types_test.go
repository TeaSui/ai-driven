package tool

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestCallInput_String(t *testing.T) {
	tc := CallInput{Input: map[string]any{"key": "value", "num": 42}}
	assert.Equal(t, "value", tc.String("key"))
	assert.Equal(t, "42", tc.String("num"))
	assert.Equal(t, "", tc.String("missing"))
}

func TestCallInput_Int(t *testing.T) {
	tc := CallInput{Input: map[string]any{
		"float": float64(10),
		"int":   5,
		"str":   "20",
		"bad":   "abc",
		"bool":  true,
	}}
	assert.Equal(t, 10, tc.Int("float", 0))
	assert.Equal(t, 5, tc.Int("int", 0))
	assert.Equal(t, 20, tc.Int("str", 0))
	assert.Equal(t, 0, tc.Int("bad", 0))
	assert.Equal(t, 99, tc.Int("missing", 99))
	assert.Equal(t, 0, tc.Int("bool", 0))
}

func TestCallInput_Bool(t *testing.T) {
	tc := CallInput{Input: map[string]any{
		"yes":  true,
		"no":   false,
		"strt": "true",
		"strf": "false",
		"bad":  "maybe",
		"num":  42,
	}}
	assert.True(t, tc.Bool("yes", false))
	assert.False(t, tc.Bool("no", true))
	assert.True(t, tc.Bool("strt", false))
	assert.False(t, tc.Bool("strf", true))
	assert.True(t, tc.Bool("bad", true))
	assert.False(t, tc.Bool("num", false))
	assert.True(t, tc.Bool("missing", true))
}

func TestSuccessOutput(t *testing.T) {
	out := SuccessOutput("id1", "ok")
	assert.Equal(t, "id1", out.ToolUseID)
	assert.Equal(t, "ok", out.Content)
	assert.False(t, out.IsError)
}

func TestErrorOutput(t *testing.T) {
	out := ErrorOutput("id2", "fail")
	assert.Equal(t, "id2", out.ToolUseID)
	assert.Equal(t, "fail", out.Content)
	assert.True(t, out.IsError)
}
