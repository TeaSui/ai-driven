package spi

import "context"

// ApprovalNotifier is the SPI for notifying external systems when an action requires approval.
type ApprovalNotifier interface {
	NotifyPending(ctx context.Context, approval *PendingApprovalContext) error
}

// PendingApprovalContext holds context for a pending approval notification.
type PendingApprovalContext struct {
	TicketKey         string `json:"ticketKey"`
	ToolName          string `json:"toolName"`
	ActionDescription string `json:"actionDescription"`
	GeneratedByModel  string `json:"generatedByModel"`
	TriggerReason     string `json:"triggerReason"`
	TimeoutSeconds    int64  `json:"timeoutSeconds"`
}
