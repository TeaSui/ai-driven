package guardrail

import (
	"fmt"
	"strings"
)

// RiskLevel represents the risk classification of a tool operation.
type RiskLevel int

const (
	// RiskLow indicates read-only operations that auto-execute.
	RiskLow RiskLevel = iota
	// RiskMedium indicates write operations that execute and notify.
	RiskMedium
	// RiskHigh indicates destructive operations that require approval.
	RiskHigh
)

// String returns the human-readable label for a RiskLevel.
func (r RiskLevel) String() string {
	switch r {
	case RiskLow:
		return "LOW"
	case RiskMedium:
		return "MEDIUM"
	case RiskHigh:
		return "HIGH"
	default:
		return "UNKNOWN"
	}
}

// ActionPolicy describes the execution policy for a given tool action.
type ActionPolicy struct {
	Level            RiskLevel
	RequiresApproval bool
	ApprovalPrompt   string
}

// AutoExecute returns a policy that allows immediate execution without approval.
func AutoExecute(level RiskLevel) ActionPolicy {
	return ActionPolicy{
		Level:            level,
		RequiresApproval: false,
	}
}

// RequireApproval returns a policy that blocks execution until approved.
func RequireApproval(level RiskLevel, prompt string) ActionPolicy {
	return ActionPolicy{
		Level:            level,
		RequiresApproval: true,
		ApprovalPrompt:   prompt,
	}
}

// RiskRule defines a pattern-based risk classification rule.
type RiskRule struct {
	Pattern   string // substring to match in tool name
	Namespace string // optional namespace restriction
	Level     RiskLevel
}

// ToolRiskRegistry assesses risk levels for tool calls using pattern matching.
type ToolRiskRegistry struct {
	rules          []RiskRule
	exactOverrides map[string]RiskLevel
}

// NewToolRiskRegistry creates a registry with default risk rules.
// Default rules (evaluated in order):
//   - HIGH: "_merge_", "_delete_"
//   - MEDIUM: "_create_branch", "_commit_files", "_create_pr", "_update_status"
//   - LOW: "_get_", "_search_", "_list_", "_add_comment"
func NewToolRiskRegistry() *ToolRiskRegistry {
	return &ToolRiskRegistry{
		rules: []RiskRule{
			{Pattern: "_merge_", Level: RiskHigh},
			{Pattern: "_delete_", Level: RiskHigh},
			{Pattern: "_create_branch", Level: RiskMedium},
			{Pattern: "_commit_files", Level: RiskMedium},
			{Pattern: "_create_pr", Level: RiskMedium},
			{Pattern: "_update_status", Level: RiskMedium},
			{Pattern: "_get_", Level: RiskLow},
			{Pattern: "_search_", Level: RiskLow},
			{Pattern: "_list_", Level: RiskLow},
			{Pattern: "_add_comment", Level: RiskLow},
		},
		exactOverrides: make(map[string]RiskLevel),
	}
}

// closedStatuses are status values that escalate update_status to HIGH risk.
var closedStatuses = map[string]bool{
	"done":     true,
	"closed":   true,
	"resolved": true,
}

// Assess determines the risk level for a tool call.
// Priority: 1) exact overrides, 2) contextual escalation, 3) pattern rules, 4) default MEDIUM.
func (r *ToolRiskRegistry) Assess(toolName string, input map[string]any) RiskLevel {
	// 1. Exact overrides take highest priority.
	if level, ok := r.exactOverrides[toolName]; ok {
		return level
	}

	// 2. Contextual escalation: update_status to done/closed/resolved = HIGH.
	if strings.Contains(toolName, "_update_status") {
		if status, ok := input["status"]; ok {
			if s, ok := status.(string); ok {
				if closedStatuses[strings.ToLower(s)] {
					return RiskHigh
				}
			}
		}
	}

	// 3. Pattern rules (first match wins).
	lower := toolName
	for _, rule := range r.rules {
		if rule.Namespace != "" && !strings.HasPrefix(lower, rule.Namespace) {
			continue
		}
		if strings.Contains(lower, rule.Pattern) {
			return rule.Level
		}
	}

	// 4. Default to MEDIUM.
	return RiskMedium
}

// BuildPolicy creates an ActionPolicy for the given tool call.
// LOW/MEDIUM results in auto-execute; HIGH requires approval.
func (r *ToolRiskRegistry) BuildPolicy(toolName string, input map[string]any) ActionPolicy {
	level := r.Assess(toolName, input)
	if level == RiskHigh {
		return RequireApproval(level, r.BuildApprovalPrompt(toolName, input))
	}
	return AutoExecute(level)
}

// BuildApprovalPrompt generates a human-readable prompt describing the high-risk action.
func (r *ToolRiskRegistry) BuildApprovalPrompt(toolName string, _ map[string]any) string {
	return fmt.Sprintf("Tool '%s' wants to perform a high-risk action", toolName)
}

// AddOverride registers an exact tool name override that takes priority over pattern rules.
func (r *ToolRiskRegistry) AddOverride(toolName string, level RiskLevel) {
	r.exactOverrides[toolName] = level
}
