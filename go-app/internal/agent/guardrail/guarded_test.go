package guardrail

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// mockNotifier records calls to NotifyPending.
type mockNotifier struct {
	calls []*spi.PendingApprovalContext
}

func (m *mockNotifier) NotifyPending(_ context.Context, approval *spi.PendingApprovalContext) error {
	m.calls = append(m.calls, approval)
	return nil
}

// fakeExecutor returns a fixed result.
func fakeExecutor(result string) ToolExecutor {
	return func(_ context.Context, _ *spi.OperationContext, _ string, _ map[string]any) (string, error) {
		return result, nil
	}
}

func TestCheckAndExecute_DisabledBypassesGuardrails(t *testing.T) {
	registry := NewGuardedRegistry(NewToolRiskRegistry(), nil, nil, false)
	op := &spi.OperationContext{}

	result, needsApproval, err := registry.CheckAndExecute(
		t.Context(), "PROJ-1", "user", "github_merge_pr",
		map[string]any{"pr": 1}, fakeExecutor("merged"), op,
	)

	require.NoError(t, err)
	assert.False(t, needsApproval)
	assert.Equal(t, "merged", result)
}

func TestCheckAndExecute_LowRiskAutoExecutes(t *testing.T) {
	notifier := &mockNotifier{}
	registry := NewGuardedRegistry(NewToolRiskRegistry(), nil, notifier, true)
	op := &spi.OperationContext{}

	result, needsApproval, err := registry.CheckAndExecute(
		t.Context(), "PROJ-1", "user", "github_get_file",
		nil, fakeExecutor("file content"), op,
	)

	require.NoError(t, err)
	assert.False(t, needsApproval)
	assert.Equal(t, "file content", result)
	assert.Empty(t, notifier.calls, "no notification for LOW risk")
}

func TestCheckAndExecute_MediumRiskAutoExecutes(t *testing.T) {
	notifier := &mockNotifier{}
	registry := NewGuardedRegistry(NewToolRiskRegistry(), nil, notifier, true)
	op := &spi.OperationContext{}

	result, needsApproval, err := registry.CheckAndExecute(
		t.Context(), "PROJ-1", "user", "github_create_branch",
		nil, fakeExecutor("branch created"), op,
	)

	require.NoError(t, err)
	assert.False(t, needsApproval)
	assert.Equal(t, "branch created", result)
	assert.Empty(t, notifier.calls, "no notification for MEDIUM risk")
}

func TestCheckAndExecute_HighRiskReturnsApprovalPrompt(t *testing.T) {
	notifier := &mockNotifier{}
	// Use a fake approval store to avoid real DynamoDB calls.
	store := NewApprovalStore(nil, "test-table")
	registry := NewGuardedRegistry(NewToolRiskRegistry(), store, notifier, true)
	op := &spi.OperationContext{}

	// We expect StorePending to fail because client is nil, but we can verify the flow
	// by checking the error.
	_, _, err := registry.CheckAndExecute(
		t.Context(), "PROJ-1", "user", "github_merge_pr",
		map[string]any{"pr_number": 42}, fakeExecutor("should not run"), op,
	)

	// The DynamoDB client is nil so StorePending will panic or error.
	// This validates the path reaches the approval flow.
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "store pending approval")
}

func TestFormatApprovalPrompt(t *testing.T) {
	prompt := formatApprovalPrompt("Tool 'github_merge_pr' wants to perform a high-risk action", RiskHigh)

	assert.Contains(t, prompt, "Approval required")
	assert.Contains(t, prompt, "Tool 'github_merge_pr' wants to perform a high-risk action")
	assert.Contains(t, prompt, "HIGH risk")
	assert.Contains(t, prompt, "@ai approve")
	assert.Contains(t, prompt, "@ai reject")
}

func TestExecuteApproved(t *testing.T) {
	store := NewApprovalStore(nil, "test-table")
	registry := NewGuardedRegistry(NewToolRiskRegistry(), store, nil, true)
	op := &spi.OperationContext{}

	approval := &PendingApproval{
		PK:            "AGENT#PROJ-1",
		SK:            "APPROVAL#2026-01-01T00:00:00Z",
		ToolName:      "github_merge_pr",
		ToolInputJSON: `{"pr_number": 42}`,
	}

	// ExecuteApproved will call the executor but ConsumeApproval will fail (nil client).
	// The executor result should still be returned.
	result, err := registry.ExecuteApproved(
		t.Context(), "PROJ-1", approval, fakeExecutor("merged"), op,
	)

	// ConsumeApproval failure is logged but does not block the result.
	require.NoError(t, err)
	assert.Equal(t, "merged", result)
}

func TestNewGuardedRegistry(t *testing.T) {
	notifier := &mockNotifier{}
	reg := NewGuardedRegistry(NewToolRiskRegistry(), nil, notifier, true)

	assert.NotNil(t, reg)
	assert.True(t, reg.enabled)
	assert.NotNil(t, reg.riskRegistry)
}
