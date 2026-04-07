package guardrail

import (
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestPendingApproval_KeyFormat(t *testing.T) {
	ticketKey := "PROJ-123"
	now := time.Now().UTC()

	pk := fmt.Sprintf("AGENT#%s", ticketKey)
	sk := fmt.Sprintf("APPROVAL#%s", now.Format(time.RFC3339Nano))
	status := "PENDING"
	riskLevel := RiskHigh.String()
	ttl := now.Unix() + ApprovalTTLSeconds

	assert.Equal(t, "AGENT#PROJ-123", pk)
	assert.Contains(t, sk, "APPROVAL#")
	assert.Equal(t, "PENDING", status)
	assert.Equal(t, "HIGH", riskLevel)
	assert.Equal(t, int64(86400), ttl-now.Unix())
}

func TestApprovalTTLSeconds(t *testing.T) {
	assert.Equal(t, int64(86400), int64(ApprovalTTLSeconds), "TTL should be 24 hours in seconds")
}

func TestNewApprovalStore(t *testing.T) {
	store := NewApprovalStore(nil, "test-table")
	assert.NotNil(t, store)
	assert.Equal(t, "test-table", store.tableName)
}
