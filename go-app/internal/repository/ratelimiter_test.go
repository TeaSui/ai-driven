package repository

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestCreateRateLimitPK(t *testing.T) {
	windowStart := time.Date(2026, 4, 4, 14, 45, 30, 0, time.UTC)
	pk := CreateRateLimitPK("tenant1", windowStart)
	// Should truncate to the hour.
	assert.Equal(t, "RATELIMIT#tenant1#2026-04-04T14", pk)
}

func TestCreateRateLimitPK_DifferentKeys(t *testing.T) {
	ts := time.Date(2026, 1, 15, 8, 0, 0, 0, time.UTC)
	assert.Equal(t, "RATELIMIT#user:abc#2026-01-15T08", CreateRateLimitPK("user:abc", ts))
}

func TestCreateRateLimitPK_MidnightBoundary(t *testing.T) {
	ts := time.Date(2026, 12, 31, 23, 59, 59, 0, time.UTC)
	pk := CreateRateLimitPK("key", ts)
	assert.Equal(t, "RATELIMIT#key#2026-12-31T23", pk)
}

func TestErrRateLimitExceeded(t *testing.T) {
	assert.NotNil(t, ErrRateLimitExceeded)
	assert.Contains(t, ErrRateLimitExceeded.Error(), "rate limit exceeded")
}
