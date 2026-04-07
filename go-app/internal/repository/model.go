package repository

import (
	"fmt"
	"time"
)

// ProcessingStatus represents the lifecycle status of a ticket being processed.
type ProcessingStatus string

const (
	StatusReceived      ProcessingStatus = "RECEIVED"
	StatusInProgress    ProcessingStatus = "IN_PROGRESS"
	StatusAnalyzing     ProcessingStatus = "ANALYZING"
	StatusGenerating    ProcessingStatus = "GENERATING"
	StatusInReview      ProcessingStatus = "IN_REVIEW"
	StatusDone          ProcessingStatus = "DONE"
	StatusFailed        ProcessingStatus = "FAILED"
	StatusSkipped       ProcessingStatus = "SKIPPED"
	StatusTestCompleted ProcessingStatus = "TEST_COMPLETED"
)

// TicketState represents the persisted state of a ticket in DynamoDB.
type TicketState struct {
	PK               string   `dynamodbav:"PK"`
	SK               string   `dynamodbav:"SK"`
	GSI1PK           string   `dynamodbav:"GSI1PK,omitempty"`
	GSI1SK           string   `dynamodbav:"GSI1SK,omitempty"`
	TicketID         string   `dynamodbav:"ticketId"`
	TicketKey        string   `dynamodbav:"ticketKey"`
	Status           string   `dynamodbav:"status"`
	AgentType        string   `dynamodbav:"agentType,omitempty"`
	PRUrl            string   `dynamodbav:"prUrl,omitempty"`
	BranchName       string   `dynamodbav:"branchName,omitempty"`
	ErrorMessage     string   `dynamodbav:"errorMessage,omitempty"`
	TTL              *int64   `dynamodbav:"ttl,omitempty"`
	CreatedAt        string   `dynamodbav:"createdAt"`
	UpdatedAt        string   `dynamodbav:"updatedAt"`
	InputTokens      *int     `dynamodbav:"inputTokens,omitempty"`
	OutputTokens     *int     `dynamodbav:"outputTokens,omitempty"`
	EstimatedCostUsd *float64 `dynamodbav:"estimatedCostUsd,omitempty"`
	CostWarningSent  *bool    `dynamodbav:"costWarningSent,omitempty"`
}

// CreateTicketPK builds the partition key for a ticket state item.
func CreateTicketPK(tenantID, ticketID string) string {
	return fmt.Sprintf("TICKET#%s#%s", tenantID, ticketID)
}

// CreateCurrentStateSK returns the sort key for the current state snapshot.
func CreateCurrentStateSK() string {
	return "STATE#CURRENT"
}

// CreateStateSK builds a sort key for a historical state entry.
func CreateStateSK(t time.Time) string {
	return fmt.Sprintf("STATE#%s", t.UTC().Format(time.RFC3339Nano))
}

// CreateIdempotencySK builds a sort key for an idempotency check.
func CreateIdempotencySK(eventID string) string {
	return fmt.Sprintf("IDEMPOTENCY#%s", eventID)
}

// CreateStatusGSI1PK builds the GSI1 partition key for status-based queries.
func CreateStatusGSI1PK(status ProcessingStatus) string {
	return fmt.Sprintf("STATUS#%s", string(status))
}

// ForTicket creates a new TicketState with standard keys populated.
func ForTicket(tenantID, ticketID, ticketKey string, status ProcessingStatus) TicketState {
	now := time.Now().UTC().Format(time.RFC3339)
	ttl := time.Now().Add(30 * 24 * time.Hour).Unix()
	return TicketState{
		PK:        CreateTicketPK(tenantID, ticketID),
		SK:        CreateCurrentStateSK(),
		GSI1PK:    CreateStatusGSI1PK(status),
		GSI1SK:    CreateTicketPK(tenantID, ticketID),
		TicketID:  ticketID,
		TicketKey: ticketKey,
		Status:    string(status),
		TTL:       &ttl,
		CreatedAt: now,
		UpdatedAt: now,
	}
}
