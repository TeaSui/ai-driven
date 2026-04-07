package repository

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

func TestCreateConversationPK(t *testing.T) {
	pk := model.CreateConversationPK("tenant1", "PROJ-42")
	assert.Equal(t, "CONV#tenant1#PROJ-42", pk)
}

func TestCreateConversationSK(t *testing.T) {
	ts := time.Date(2026, 4, 4, 12, 0, 0, 0, time.UTC)
	sk := model.CreateConversationSK(ts, 1)
	assert.Contains(t, sk, "MSG#2026-04-04T12:00:00")
	assert.Contains(t, sk, "#0001")
}

func TestCreateConversationSK_Ordering(t *testing.T) {
	ts := time.Date(2026, 4, 4, 12, 0, 0, 0, time.UTC)
	sk1 := model.CreateConversationSK(ts, 1)
	sk2 := model.CreateConversationSK(ts, 2)
	assert.Less(t, sk1, sk2, "earlier sequence should sort before later")
}

func TestDefaultTTL(t *testing.T) {
	ttl := model.DefaultTTL()
	// Should be roughly 30 days from now.
	expected := time.Now().Add(30 * 24 * time.Hour).Unix()
	assert.InDelta(t, expected, ttl, 5, "TTL should be ~30 days from now")
}

func TestConversationQueryLimit(t *testing.T) {
	assert.Equal(t, 100, conversationQueryLimit)
}

func TestDynamoConversationRepository_ImplementsInterface(t *testing.T) {
	// Compile-time check that DynamoConversationRepository satisfies ConversationRepository.
	var _ ConversationRepository = (*DynamoConversationRepository)(nil)
}
