package spi

import "context"

// IssueTrackerProvider is the SPI for issue tracking platforms (Jira, Linear, GitHub Issues).
type IssueTrackerProvider interface {
	Name() string
	GetTicketDetails(ctx context.Context, op *OperationContext, ticketKey string) (map[string]any, error)
	PostComment(ctx context.Context, op *OperationContext, ticketKey, comment string) error
	UpdateLabels(ctx context.Context, op *OperationContext, ticketKey string, addLabels, removeLabels []string) error
	UpdateStatus(ctx context.Context, op *OperationContext, ticketKey, status string) error
}
