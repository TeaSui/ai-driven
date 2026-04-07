package claude

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/cenkalti/backoff/v4"
	"github.com/rs/zerolog/log"
)

// RetryConfig controls retry behavior for API calls.
type RetryConfig struct {
	MaxRetries      int
	InitialInterval time.Duration
	Multiplier      float64
	MaxInterval     time.Duration
}

// DefaultRetryConfig returns the standard retry config: 3 attempts, 1s initial, 2x multiplier, 30s max.
func DefaultRetryConfig() RetryConfig {
	return RetryConfig{
		MaxRetries:      3,
		InitialInterval: 1 * time.Second,
		Multiplier:      2.0,
		MaxInterval:     30 * time.Second,
	}
}

// WithRetry executes fn with exponential backoff. It retries on retryable errors
// (429, 5xx) and stops on permanent errors (4xx except 429).
func WithRetry[T any](ctx context.Context, cfg RetryConfig, fn func() (T, error)) (T, error) {
	b := backoff.NewExponentialBackOff()
	b.InitialInterval = cfg.InitialInterval
	b.Multiplier = cfg.Multiplier
	b.MaxInterval = cfg.MaxInterval
	b.MaxElapsedTime = 0 // controlled by MaxRetries instead

	maxRetries := cfg.MaxRetries
	if maxRetries < 0 {
		maxRetries = 0
	}
	bWithMax := backoff.WithMaxRetries(b, uint64(maxRetries)) //nolint:gosec // bounds checked above
	bWithCtx := backoff.WithContext(bWithMax, ctx)

	var result T
	operation := func() error {
		var err error
		result, err = fn()
		if err == nil {
			return nil
		}
		if isPermanentError(err) {
			return backoff.Permanent(err)
		}
		log.Ctx(ctx).Warn().Err(err).Msg("retryable Claude API error, will retry")
		return err
	}

	if err := backoff.Retry(operation, bWithCtx); err != nil {
		var zero T
		return zero, err
	}
	return result, nil
}

// isPermanentError returns true for errors that should not be retried (4xx except 429).
func isPermanentError(err error) bool {
	var apiErr *APIError
	if !errors.As(err, &apiErr) {
		return false
	}
	// 429 (rate limit) and 5xx are retryable
	if apiErr.StatusCode == 429 || apiErr.StatusCode >= 500 {
		return false
	}
	// All other 4xx are permanent
	return apiErr.StatusCode >= 400 && apiErr.StatusCode < 500
}

// APIError represents an error response from the Claude API.
type APIError struct {
	StatusCode int
	Type       string `json:"type"`
	Message    string `json:"message"`
}

func (e *APIError) Error() string {
	return fmt.Sprintf("claude API error %d: %s", e.StatusCode, e.Message)
}

// AsAPIError extracts an APIError from err if present.
func AsAPIError(err error) (*APIError, bool) {
	var apiErr *APIError
	if errors.As(err, &apiErr) {
		return apiErr, true
	}
	return nil, false
}
