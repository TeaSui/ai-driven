package tool

import (
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestReadOnlyProvider_ToolDefinitions_FiltersWriteTools(t *testing.T) {
	delegate := newMockProvider("sc",
		"sc_get_file",
		"sc_list_files",
		"sc_create_branch",
		"sc_commit_changes",
		"sc_push_branch",
		"sc_delete_branch",
		"sc_update_file",
		"sc_write_file",
		"sc_post_comment",
		"sc_apply_patch",
	)

	ro := NewReadOnlyProvider(delegate)

	tools := ro.ToolDefinitions()
	require.Len(t, tools, 2)
	assert.Equal(t, "sc_get_file", tools[0].Name)
	assert.Equal(t, "sc_list_files", tools[1].Name)
}

func TestReadOnlyProvider_ToolDefinitions_AllReadOnly(t *testing.T) {
	delegate := newMockProvider("sc", "sc_get_file", "sc_list_files", "sc_search")
	ro := NewReadOnlyProvider(delegate)
	tools := ro.ToolDefinitions()
	assert.Len(t, tools, 3)
}

func TestReadOnlyProvider_Namespace(t *testing.T) {
	delegate := newMockProvider("source_control")
	ro := NewReadOnlyProvider(delegate)
	assert.Equal(t, "source_control", ro.Namespace())
}

func TestReadOnlyProvider_MaxOutputChars(t *testing.T) {
	delegate := &mockProvider{namespace: "ns", maxOutput: 5000}
	ro := NewReadOnlyProvider(delegate)
	assert.Equal(t, 5000, ro.MaxOutputChars())
}

func TestReadOnlyProvider_Execute_AllowsReadTool(t *testing.T) {
	delegate := &mockProvider{
		namespace: "sc",
		execFn: func(call Call) Result {
			return Success(call.ID, "file contents here")
		},
	}
	ro := NewReadOnlyProvider(delegate)

	ctx := t.Context()
	op := &spi.OperationContext{TenantID: "test"}

	result := ro.Execute(ctx, op, Call{ID: "c1", Name: "sc_get_file"})
	assert.False(t, result.IsError)
	assert.Equal(t, "file contents here", result.Content)
}

func TestReadOnlyProvider_Execute_RejectsWriteTool(t *testing.T) {
	delegate := &mockProvider{
		namespace: "sc",
		execFn: func(call Call) Result {
			return Success(call.ID, "should not reach")
		},
	}
	ro := NewReadOnlyProvider(delegate)

	ctx := t.Context()
	op := &spi.OperationContext{TenantID: "test"}

	for _, toolName := range []string{
		"sc_create_branch",
		"sc_commit_changes",
		"sc_push_branch",
		"sc_delete_file",
		"sc_update_config",
		"sc_write_data",
		"sc_post_review",
		"sc_apply_patch",
	} {
		result := ro.Execute(ctx, op, Call{ID: "c1", Name: toolName})
		assert.True(t, result.IsError, "expected error for tool %s", toolName)
		assert.Contains(t, result.Content, "not allowed in read-only mode")
	}
}

func TestIsReadOnly_CaseInsensitive(t *testing.T) {
	// "Create" with capital C should still be denied.
	assert.False(t, isReadOnly("sc_Create_branch"))
	assert.False(t, isReadOnly("SC_DELETE_FILE"))
	assert.True(t, isReadOnly("sc_get_file"))
	assert.True(t, isReadOnly("sc_SEARCH_code"))
}
