package tool

import (
	"context"
	"strings"
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// mockProvider implements Provider for testing.
type mockProvider struct {
	namespace string
	tools     []Tool
	execFn    func(Call) Result
	maxOutput int
}

func (m *mockProvider) Namespace() string       { return m.namespace }
func (m *mockProvider) ToolDefinitions() []Tool { return m.tools }
func (m *mockProvider) MaxOutputChars() int {
	if m.maxOutput > 0 {
		return m.maxOutput
	}
	return DefaultMaxOutputChars
}

func (m *mockProvider) Execute(_ context.Context, _ *spi.OperationContext, call Call) Result {
	if m.execFn != nil {
		return m.execFn(call)
	}
	return Success(call.ID, "executed "+call.Name)
}

func newMockProvider(namespace string, toolNames ...string) *mockProvider {
	tools := make([]Tool, 0, len(toolNames))
	for _, name := range toolNames {
		tools = append(tools, Tool{Name: name, Description: "mock " + name})
	}
	return &mockProvider{namespace: namespace, tools: tools}
}

func TestRegistry_Register_And_GetAllToolDefinitions(t *testing.T) {
	reg := NewRegistry()
	reg.Register(newMockProvider("source_control", "source_control_get_file", "source_control_list_files"))
	reg.Register(newMockProvider("issue_tracker", "issue_tracker_get_issue"))

	tools := reg.GetAllToolDefinitions()
	require.Len(t, tools, 3)
	assert.Equal(t, "source_control_get_file", tools[0].Name)
	assert.Equal(t, "source_control_list_files", tools[1].Name)
	assert.Equal(t, "issue_tracker_get_issue", tools[2].Name)
}

func TestRegistry_GetAllToolDefinitions_Empty(t *testing.T) {
	reg := NewRegistry()
	tools := reg.GetAllToolDefinitions()
	assert.Empty(t, tools)
}

func TestRegistry_Execute_RoutesToCorrectProvider(t *testing.T) {
	reg := NewRegistry()
	reg.Register(&mockProvider{
		namespace: "source_control",
		tools:     []Tool{{Name: "source_control_get_file"}},
		execFn: func(call Call) Result {
			return Success(call.ID, "from_source_control")
		},
	})
	reg.Register(&mockProvider{
		namespace: "issue_tracker",
		tools:     []Tool{{Name: "issue_tracker_get_issue"}},
		execFn: func(call Call) Result {
			return Success(call.ID, "from_issue_tracker")
		},
	})

	ctx := t.Context()
	op := &spi.OperationContext{TenantID: "test"}

	r1 := reg.Execute(ctx, op, Call{ID: "c1", Name: "source_control_get_file"})
	assert.Equal(t, "from_source_control", r1.Content)
	assert.False(t, r1.IsError)

	r2 := reg.Execute(ctx, op, Call{ID: "c2", Name: "issue_tracker_get_issue"})
	assert.Equal(t, "from_issue_tracker", r2.Content)
	assert.False(t, r2.IsError)
}

func TestRegistry_Execute_UnknownNamespace(t *testing.T) {
	reg := NewRegistry()
	ctx := t.Context()
	op := &spi.OperationContext{TenantID: "test"}

	result := reg.Execute(ctx, op, Call{ID: "c1", Name: "unknown_do_stuff"})
	assert.True(t, result.IsError)
	assert.Contains(t, result.Content, "no provider registered")
}

func TestRegistry_Execute_PanicRecovery(t *testing.T) {
	reg := NewRegistry()
	reg.Register(&mockProvider{
		namespace: "panicky",
		tools:     []Tool{{Name: "panicky_crash"}},
		execFn: func(_ Call) Result {
			panic("something went terribly wrong")
		},
	})

	ctx := t.Context()
	op := &spi.OperationContext{TenantID: "test"}

	result := reg.Execute(ctx, op, Call{ID: "c1", Name: "panicky_crash"})
	assert.True(t, result.IsError)
	assert.Contains(t, result.Content, "panic during tool execution")
	assert.Contains(t, result.Content, "something went terribly wrong")
}

func TestRegistry_Execute_Truncation(t *testing.T) {
	longContent := strings.Repeat("x", 100)
	reg := NewRegistry()
	reg.Register(&mockProvider{
		namespace: "verbose",
		tools:     []Tool{{Name: "verbose_big_output"}},
		maxOutput: 50,
		execFn: func(call Call) Result {
			return Success(call.ID, longContent)
		},
	})

	ctx := t.Context()
	op := &spi.OperationContext{TenantID: "test"}

	result := reg.Execute(ctx, op, Call{ID: "c1", Name: "verbose_big_output"})
	assert.False(t, result.IsError)
	assert.Contains(t, result.Content, "[OUTPUT TRUNCATED")
	assert.Contains(t, result.Content, "100 total chars")
	// The first 50 chars should be preserved.
	assert.True(t, strings.HasPrefix(result.Content, strings.Repeat("x", 50)))
}

func TestRegistry_Execute_NoTruncationWhenUnderLimit(t *testing.T) {
	reg := NewRegistry()
	reg.Register(&mockProvider{
		namespace: "brief",
		tools:     []Tool{{Name: "brief_small_output"}},
		maxOutput: 100,
		execFn: func(call Call) Result {
			return Success(call.ID, "short")
		},
	})

	ctx := t.Context()
	op := &spi.OperationContext{TenantID: "test"}

	result := reg.Execute(ctx, op, Call{ID: "c1", Name: "brief_small_output"})
	assert.Equal(t, "short", result.Content)
}

func TestRegistry_ExtractNamespace(t *testing.T) {
	reg := NewRegistry()
	reg.Register(newMockProvider("source_control"))
	reg.Register(newMockProvider("source_control_advanced"))
	reg.Register(newMockProvider("issue_tracker"))

	// Longest prefix match: "source_control_advanced" matches over "source_control"
	assert.Equal(t, "source_control_advanced", reg.ExtractNamespace("source_control_advanced_get_file"))
	assert.Equal(t, "source_control", reg.ExtractNamespace("source_control_get_file"))
	assert.Equal(t, "issue_tracker", reg.ExtractNamespace("issue_tracker_get_issue"))
	assert.Equal(t, "", reg.ExtractNamespace("unknown_tool"))
}

func TestRegistry_RegisteredNamespaces(t *testing.T) {
	reg := NewRegistry()
	reg.Register(newMockProvider("source_control"))
	reg.Register(newMockProvider("issue_tracker"))
	reg.Register(newMockProvider("code_context"))

	ns := reg.RegisteredNamespaces()
	assert.Equal(t, []string{"code_context", "issue_tracker", "source_control"}, ns)
}

func TestRegistry_RegisteredNamespaces_Empty(t *testing.T) {
	reg := NewRegistry()
	ns := reg.RegisteredNamespaces()
	assert.Empty(t, ns)
}

func TestRegistry_Register_OverwritesSameNamespace(t *testing.T) {
	reg := NewRegistry()
	reg.Register(newMockProvider("ns", "ns_tool_a"))
	reg.Register(newMockProvider("ns", "ns_tool_b"))

	tools := reg.GetAllToolDefinitions()
	require.Len(t, tools, 1)
	assert.Equal(t, "ns_tool_b", tools[0].Name)
}

func TestCoreNamespaces(t *testing.T) {
	assert.True(t, CoreNamespaces["source_control"])
	assert.True(t, CoreNamespaces["issue_tracker"])
	assert.True(t, CoreNamespaces["code_context"])
	assert.False(t, CoreNamespaces["unknown"])
}
