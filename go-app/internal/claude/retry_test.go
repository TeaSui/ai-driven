package claude

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func fastRetryConfig() RetryConfig {
	return RetryConfig{
		MaxRetries:      2,
		InitialInterval: 1 * time.Millisecond,
		Multiplier:      1.0,
		MaxInterval:     5 * time.Millisecond,
	}
}

func TestWithRetry_SuccessOnFirstAttempt(t *testing.T) {
	result, err := WithRetry(t.Context(), fastRetryConfig(), func() (string, error) {
		return "ok", nil
	})
	require.NoError(t, err)
	assert.Equal(t, "ok", result)
}

func TestWithRetry_SuccessAfterRetries(t *testing.T) {
	attempts := 0
	result, err := WithRetry(t.Context(), fastRetryConfig(), func() (string, error) {
		attempts++
		if attempts < 3 {
			return "", &APIError{StatusCode: 500, Message: "server error"}
		}
		return "recovered", nil
	})
	require.NoError(t, err)
	assert.Equal(t, "recovered", result)
	assert.Equal(t, 3, attempts)
}

func TestWithRetry_PermanentError(t *testing.T) {
	attempts := 0
	_, err := WithRetry(t.Context(), fastRetryConfig(), func() (string, error) {
		attempts++
		return "", &APIError{StatusCode: 400, Message: "bad request"}
	})
	require.Error(t, err)
	assert.Equal(t, 1, attempts) // No retry for 400
	var apiErr *APIError
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, 400, apiErr.StatusCode)
}

func TestWithRetry_RateLimitRetries(t *testing.T) {
	attempts := 0
	_, err := WithRetry(t.Context(), fastRetryConfig(), func() (string, error) {
		attempts++
		return "", &APIError{StatusCode: 429, Message: "rate limited"}
	})
	require.Error(t, err)        // Exhausts retries
	assert.Equal(t, 3, attempts) // 1 initial + 2 retries
}

func TestWithRetry_ContextCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	cancel()

	_, err := WithRetry(ctx, fastRetryConfig(), func() (string, error) {
		return "", &APIError{StatusCode: 500, Message: "server error"}
	})
	require.Error(t, err)
}

func TestWithRetry_NonAPIError(t *testing.T) {
	attempts := 0
	_, err := WithRetry(t.Context(), fastRetryConfig(), func() (string, error) {
		attempts++
		return "", errors.New("network error")
	})
	require.Error(t, err)
	assert.Equal(t, 3, attempts) // Non-API errors are retried
}

func TestAPIError_Error(t *testing.T) {
	err := &APIError{StatusCode: 429, Type: "rate_limit_error", Message: "too many requests"}
	assert.Equal(t, "claude API error 429: too many requests", err.Error())
}

func TestAsAPIError(t *testing.T) {
	t.Run("api error", func(t *testing.T) {
		err := &APIError{StatusCode: 400}
		apiErr, ok := AsAPIError(err)
		assert.True(t, ok)
		assert.Equal(t, 400, apiErr.StatusCode)
	})

	t.Run("wrapped api error", func(t *testing.T) {
		inner := &APIError{StatusCode: 500}
		wrapped := errors.Join(errors.New("context"), inner)
		apiErr, ok := AsAPIError(wrapped)
		assert.True(t, ok)
		assert.Equal(t, 500, apiErr.StatusCode)
	})

	t.Run("non-api error", func(t *testing.T) {
		_, ok := AsAPIError(errors.New("random"))
		assert.False(t, ok)
	})
}

func TestIsPermanentError(t *testing.T) {
	tests := []struct {
		name      string
		err       error
		permanent bool
	}{
		{"400 bad request", &APIError{StatusCode: 400}, true},
		{"401 unauthorized", &APIError{StatusCode: 401}, true},
		{"403 forbidden", &APIError{StatusCode: 403}, true},
		{"404 not found", &APIError{StatusCode: 404}, true},
		{"429 rate limit", &APIError{StatusCode: 429}, false},
		{"500 server error", &APIError{StatusCode: 500}, false},
		{"502 bad gateway", &APIError{StatusCode: 502}, false},
		{"503 service unavailable", &APIError{StatusCode: 503}, false},
		{"non-API error", errors.New("network"), false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			assert.Equal(t, tt.permanent, isPermanentError(tt.err))
		})
	}
}
