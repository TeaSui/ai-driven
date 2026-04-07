package guardrail

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// ToolExecutor is a function type that executes a tool call and returns its result.
type ToolExecutor func(ctx context.Context, op *spi.OperationContext, toolName string, input map[string]any) (string, error)

// GuardedRegistry wraps tool execution with risk-based approval gating.
type GuardedRegistry struct {
	riskRegistry     *ToolRiskRegistry
	approvalStore    *ApprovalStore
	approvalNotifier spi.ApprovalNotifier
	enabled          bool
}

// NewGuardedRegistry creates a new guarded registry.
// When enabled is false, all tool calls bypass risk checks.
func NewGuardedRegistry(
	riskRegistry *ToolRiskRegistry,
	approvalStore *ApprovalStore,
	notifier spi.ApprovalNotifier,
	enabled bool,
) *GuardedRegistry {
	return &GuardedRegistry{
		riskRegistry:     riskRegistry,
		approvalStore:    approvalStore,
		approvalNotifier: notifier,
		enabled:          enabled,
	}
}

// CheckAndExecute evaluates a tool call against the risk registry and either executes it
// immediately or gates it behind an approval flow.
// Returns: (result, needsApproval, error).
func (g *GuardedRegistry) CheckAndExecute(
	ctx context.Context,
	ticketKey, requestedBy, toolName string,
	input map[string]any,
	executor ToolExecutor,
	op *spi.OperationContext,
) (result string, needsApproval bool, err error) {
	if !g.enabled {
		result, execErr := executor(ctx, op, toolName, input)
		return result, false, execErr
	}

	policy := g.riskRegistry.BuildPolicy(toolName, input)

	if !policy.RequiresApproval {
		log.Ctx(ctx).Debug().
			Str("toolName", toolName).
			Str("riskLevel", policy.Level.String()).
			Msg("Auto-executing tool (no approval required)")

		result, execErr := executor(ctx, op, toolName, input)
		return result, false, execErr
	}

	// HIGH risk: store pending approval and notify.
	inputJSON, err := json.Marshal(input)
	if err != nil {
		return "", false, fmt.Errorf("marshal tool input: %w", err)
	}

	toolCallID := fmt.Sprintf("%s-%s", ticketKey, toolName)

	err = g.approvalStore.StorePending(
		ctx, ticketKey, toolCallID, toolName, string(inputJSON),
		policy.Level, policy.ApprovalPrompt, requestedBy,
	)
	if err != nil {
		return "", false, fmt.Errorf("store pending approval: %w", err)
	}

	if g.approvalNotifier != nil {
		notifyErr := g.approvalNotifier.NotifyPending(ctx, &spi.PendingApprovalContext{
			TicketKey:         ticketKey,
			ToolName:          toolName,
			ActionDescription: policy.ApprovalPrompt,
			TimeoutSeconds:    ApprovalTTLSeconds,
		})
		if notifyErr != nil {
			log.Ctx(ctx).Warn().Err(notifyErr).
				Str("ticketKey", ticketKey).
				Str("toolName", toolName).
				Msg("Failed to send approval notification")
		}
	}

	prompt := formatApprovalPrompt(policy.ApprovalPrompt, policy.Level)
	return prompt, true, nil
}

// ExecuteApproved reconstructs and executes a previously approved tool call,
// then consumes the approval record.
func (g *GuardedRegistry) ExecuteApproved(
	ctx context.Context,
	ticketKey string,
	approval *PendingApproval,
	executor ToolExecutor,
	op *spi.OperationContext,
) (string, error) {
	var input map[string]any
	if err := json.Unmarshal([]byte(approval.ToolInputJSON), &input); err != nil {
		return "", fmt.Errorf("unmarshal approved tool input: %w", err)
	}

	result, err := executor(ctx, op, approval.ToolName, input)
	if err != nil {
		return "", fmt.Errorf("execute approved tool '%s': %w", approval.ToolName, err)
	}

	if consumeErr := g.approvalStore.ConsumeApproval(ctx, ticketKey, approval.SK); consumeErr != nil {
		log.Ctx(ctx).Warn().Err(consumeErr).
			Str("ticketKey", ticketKey).
			Str("toolName", approval.ToolName).
			Msg("Failed to consume approval after execution")
	}

	return result, nil
}

// formatApprovalPrompt builds the user-facing approval message.
func formatApprovalPrompt(prompt string, level RiskLevel) string {
	return fmt.Sprintf(
		"\u26a0\ufe0f *Approval required* — %s\n\nThis action is classified as *%s risk*.\nReply with `@ai approve` to proceed, or `@ai reject` to cancel.",
		prompt, level,
	)
}
