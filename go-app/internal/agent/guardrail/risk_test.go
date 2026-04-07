package guardrail

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRiskLevel_String(t *testing.T) {
	tests := []struct {
		level    RiskLevel
		expected string
	}{
		{RiskLow, "LOW"},
		{RiskMedium, "MEDIUM"},
		{RiskHigh, "HIGH"},
		{RiskLevel(99), "UNKNOWN"},
	}
	for _, tt := range tests {
		assert.Equal(t, tt.expected, tt.level.String())
	}
}

func TestAutoExecute(t *testing.T) {
	p := AutoExecute(RiskLow)
	assert.Equal(t, RiskLow, p.Level)
	assert.False(t, p.RequiresApproval)
	assert.Empty(t, p.ApprovalPrompt)
}

func TestRequireApproval(t *testing.T) {
	p := RequireApproval(RiskHigh, "dangerous action")
	assert.Equal(t, RiskHigh, p.Level)
	assert.True(t, p.RequiresApproval)
	assert.Equal(t, "dangerous action", p.ApprovalPrompt)
}

func TestAssess_HighRiskPatterns(t *testing.T) {
	reg := NewToolRiskRegistry()

	tests := []struct {
		name     string
		toolName string
	}{
		{"merge pattern", "github_merge_pr"},
		{"delete pattern", "jira_delete_issue"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			level := reg.Assess(tt.toolName, nil)
			assert.Equal(t, RiskHigh, level, "expected HIGH for %s", tt.toolName)
		})
	}
}

func TestAssess_MediumRiskPatterns(t *testing.T) {
	reg := NewToolRiskRegistry()

	tests := []struct {
		name     string
		toolName string
	}{
		{"create branch", "github_create_branch"},
		{"commit files", "github_commit_files"},
		{"create pr", "github_create_pr"},
		{"update status", "jira_update_status"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			level := reg.Assess(tt.toolName, nil)
			assert.Equal(t, RiskMedium, level, "expected MEDIUM for %s", tt.toolName)
		})
	}
}

func TestAssess_LowRiskPatterns(t *testing.T) {
	reg := NewToolRiskRegistry()

	tests := []struct {
		name     string
		toolName string
	}{
		{"get pattern", "github_get_file"},
		{"search pattern", "jira_search_issues"},
		{"list pattern", "github_list_branches"},
		{"add comment", "jira_add_comment"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			level := reg.Assess(tt.toolName, nil)
			assert.Equal(t, RiskLow, level, "expected LOW for %s", tt.toolName)
		})
	}
}

func TestAssess_ContextualEscalation_UpdateStatusToDone(t *testing.T) {
	reg := NewToolRiskRegistry()

	escalatingStatuses := []string{"done", "closed", "resolved", "Done", "CLOSED", "Resolved"}
	for _, status := range escalatingStatuses {
		t.Run(status, func(t *testing.T) {
			input := map[string]any{"status": status}
			level := reg.Assess("jira_update_status", input)
			assert.Equal(t, RiskHigh, level, "expected HIGH for update_status to %s", status)
		})
	}
}

func TestAssess_ContextualEscalation_UpdateStatusNonClosing(t *testing.T) {
	reg := NewToolRiskRegistry()

	input := map[string]any{"status": "in_progress"}
	level := reg.Assess("jira_update_status", input)
	assert.Equal(t, RiskMedium, level, "non-closing status should remain MEDIUM")
}

func TestAssess_ExactOverrideTakesPriority(t *testing.T) {
	reg := NewToolRiskRegistry()
	reg.AddOverride("github_get_file", RiskHigh)

	level := reg.Assess("github_get_file", nil)
	assert.Equal(t, RiskHigh, level, "override should take priority over pattern")
}

func TestAssess_OverrideTakesPriorityOverContextual(t *testing.T) {
	reg := NewToolRiskRegistry()
	reg.AddOverride("jira_update_status", RiskLow)

	input := map[string]any{"status": "done"}
	level := reg.Assess("jira_update_status", input)
	assert.Equal(t, RiskLow, level, "override should take priority over contextual escalation")
}

func TestAssess_DefaultMedium(t *testing.T) {
	reg := NewToolRiskRegistry()

	level := reg.Assess("some_unknown_tool", nil)
	assert.Equal(t, RiskMedium, level, "unknown tools should default to MEDIUM")
}

func TestAssess_NamespaceRestriction(t *testing.T) {
	reg := NewToolRiskRegistry()
	reg.rules = append([]RiskRule{
		{Pattern: "_deploy", Namespace: "prod_", Level: RiskHigh},
	}, reg.rules...)

	assert.Equal(t, RiskHigh, reg.Assess("prod_deploy", nil))
	// Without the namespace prefix, the rule should not match; falls to default MEDIUM.
	assert.Equal(t, RiskMedium, reg.Assess("staging_deploy", nil))
}

func TestBuildPolicy_LowAutoExecutes(t *testing.T) {
	reg := NewToolRiskRegistry()
	policy := reg.BuildPolicy("github_get_file", nil)

	assert.Equal(t, RiskLow, policy.Level)
	assert.False(t, policy.RequiresApproval)
}

func TestBuildPolicy_MediumAutoExecutes(t *testing.T) {
	reg := NewToolRiskRegistry()
	policy := reg.BuildPolicy("github_create_branch", nil)

	assert.Equal(t, RiskMedium, policy.Level)
	assert.False(t, policy.RequiresApproval)
}

func TestBuildPolicy_HighRequiresApproval(t *testing.T) {
	reg := NewToolRiskRegistry()
	policy := reg.BuildPolicy("github_merge_pr", nil)

	require.Equal(t, RiskHigh, policy.Level)
	assert.True(t, policy.RequiresApproval)
	assert.Contains(t, policy.ApprovalPrompt, "github_merge_pr")
	assert.Contains(t, policy.ApprovalPrompt, "high-risk action")
}

func TestBuildApprovalPrompt(t *testing.T) {
	reg := NewToolRiskRegistry()
	prompt := reg.BuildApprovalPrompt("github_delete_branch", nil)

	assert.Equal(t, "Tool 'github_delete_branch' wants to perform a high-risk action", prompt)
}

func TestAddOverride(t *testing.T) {
	reg := NewToolRiskRegistry()
	reg.AddOverride("custom_tool", RiskHigh)

	level := reg.Assess("custom_tool", nil)
	assert.Equal(t, RiskHigh, level)
}
