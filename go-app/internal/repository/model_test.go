package repository

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCreateTicketPK(t *testing.T) {
	assert.Equal(t, "TICKET#tenant1#ticket42", CreateTicketPK("tenant1", "ticket42"))
}

func TestCreateCurrentStateSK(t *testing.T) {
	assert.Equal(t, "STATE#CURRENT", CreateCurrentStateSK())
}

func TestCreateStateSK(t *testing.T) {
	ts := time.Date(2026, 4, 4, 12, 30, 0, 0, time.UTC)
	sk := CreateStateSK(ts)
	assert.Contains(t, sk, "STATE#2026-04-04T12:30:00")
}

func TestCreateIdempotencySK(t *testing.T) {
	assert.Equal(t, "IDEMPOTENCY#evt-123", CreateIdempotencySK("evt-123"))
}

func TestCreateStatusGSI1PK(t *testing.T) {
	assert.Equal(t, "STATUS#IN_PROGRESS", CreateStatusGSI1PK(StatusInProgress))
	assert.Equal(t, "STATUS#DONE", CreateStatusGSI1PK(StatusDone))
	assert.Equal(t, "STATUS#FAILED", CreateStatusGSI1PK(StatusFailed))
}

func TestProcessingStatusValues(t *testing.T) {
	statuses := []ProcessingStatus{
		StatusReceived, StatusInProgress, StatusAnalyzing, StatusGenerating,
		StatusInReview, StatusDone, StatusFailed, StatusSkipped, StatusTestCompleted,
	}
	assert.Len(t, statuses, 9)
	// Verify no duplicates.
	seen := make(map[ProcessingStatus]bool)
	for _, s := range statuses {
		assert.False(t, seen[s], "duplicate status: %s", s)
		seen[s] = true
	}
}

func TestForTicket(t *testing.T) {
	state := ForTicket("tenant1", "ticket42", "PROJ-42", StatusReceived)

	assert.Equal(t, "TICKET#tenant1#ticket42", state.PK)
	assert.Equal(t, "STATE#CURRENT", state.SK)
	assert.Equal(t, "STATUS#RECEIVED", state.GSI1PK)
	assert.Equal(t, "TICKET#tenant1#ticket42", state.GSI1SK)
	assert.Equal(t, "ticket42", state.TicketID)
	assert.Equal(t, "PROJ-42", state.TicketKey)
	assert.Equal(t, "RECEIVED", state.Status)
	require.NotNil(t, state.TTL)
	assert.Greater(t, *state.TTL, time.Now().Unix())
	assert.NotEmpty(t, state.CreatedAt)
	assert.NotEmpty(t, state.UpdatedAt)
}

func TestForTicket_DifferentStatuses(t *testing.T) {
	tests := []struct {
		status   ProcessingStatus
		expected string
	}{
		{StatusInProgress, "STATUS#IN_PROGRESS"},
		{StatusDone, "STATUS#DONE"},
		{StatusFailed, "STATUS#FAILED"},
	}
	for _, tc := range tests {
		t.Run(string(tc.status), func(t *testing.T) {
			state := ForTicket("t", "id", "key", tc.status)
			assert.Equal(t, tc.expected, state.GSI1PK)
			assert.Equal(t, string(tc.status), state.Status)
		})
	}
}
