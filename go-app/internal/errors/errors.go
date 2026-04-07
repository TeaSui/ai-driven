package errors

import (
	"errors"
	"fmt"
)

// Domain sentinel errors.
var (
	ErrBudgetExhausted    = errors.New("token budget exhausted")
	ErrMaxTurnsReached    = errors.New("max turns reached")
	ErrCircuitBreakerOpen = errors.New("circuit breaker open")
	ErrRateLimitExceeded  = errors.New("rate limit exceeded")
	ErrValidation         = errors.New("validation error")
)

// HTTPClientError represents an HTTP error from an external service.
type HTTPClientError struct {
	StatusCode   int
	ResponseBody string
	Message      string
}

func (e *HTTPClientError) Error() string {
	return fmt.Sprintf("HTTP %d: %s", e.StatusCode, e.Message)
}

// RateLimitError represents a rate limit response from a service.
type RateLimitError struct {
	Service           string
	RetryAfterSeconds int64
}

func (e *RateLimitError) Error() string {
	return fmt.Sprintf("rate limited by %s, retry after %d seconds", e.Service, e.RetryAfterSeconds)
}

func (e *RateLimitError) Unwrap() error {
	return ErrRateLimitExceeded
}
