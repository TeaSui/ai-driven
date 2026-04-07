package resilience

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// State represents the circuit breaker state.
type State int

const (
	StateClosed State = iota
	StateOpen
	StateHalfOpen
)

func (s State) String() string {
	switch s {
	case StateClosed:
		return "CLOSED"
	case StateOpen:
		return "OPEN"
	case StateHalfOpen:
		return "HALF_OPEN"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", int(s))
	}
}

// ErrCircuitBreakerOpen is returned when the circuit breaker is open and rejects calls.
var ErrCircuitBreakerOpen = errors.New("circuit breaker is open")

// Config holds configuration for a CircuitBreaker.
type Config struct {
	FailureThreshold int
	SuccessThreshold int
	TimeoutDuration  time.Duration
}

// DefaultConfig returns a balanced circuit breaker configuration (5 failures, 2 successes, 30s timeout).
func DefaultConfig() Config {
	return Config{
		FailureThreshold: 5,
		SuccessThreshold: 2,
		TimeoutDuration:  30 * time.Second,
	}
}

// AggressiveConfig returns a fast-tripping configuration (3 failures, 1 success, 10s timeout).
func AggressiveConfig() Config {
	return Config{
		FailureThreshold: 3,
		SuccessThreshold: 1,
		TimeoutDuration:  10 * time.Second,
	}
}

// LenientConfig returns a tolerant configuration (10 failures, 5 successes, 60s timeout).
func LenientConfig() Config {
	return Config{
		FailureThreshold: 10,
		SuccessThreshold: 5,
		TimeoutDuration:  60 * time.Second,
	}
}

// Metrics tracks circuit breaker counters.
type Metrics struct {
	failureCount  int
	successCount  int
	totalCalls    int
	totalFailures int
}

// CircuitBreaker implements the circuit breaker pattern for fault tolerance.
type CircuitBreaker struct {
	name            string
	config          Config
	state           atomic.Value // stores State
	metrics         *Metrics
	mu              sync.Mutex
	lastFailureTime time.Time
	now             func() time.Time // for testing
}

// New creates a new CircuitBreaker with the given name and configuration.
func New(name string, cfg Config) *CircuitBreaker {
	cb := &CircuitBreaker{
		name:    name,
		config:  cfg,
		metrics: &Metrics{},
		now:     time.Now,
	}
	cb.state.Store(StateClosed)
	return cb
}

// loadState returns the current state from the atomic value.
// This is safe because we only ever store State values via state.Store().
func (cb *CircuitBreaker) loadState() State {
	return cb.state.Load().(State) //nolint:errcheck // only State values are stored
}

// State returns the current state of the circuit breaker.
func (cb *CircuitBreaker) State() State {
	return cb.loadState()
}

// Reset resets the circuit breaker to the CLOSED state with zeroed metrics.
func (cb *CircuitBreaker) Reset() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.state.Store(StateClosed)
	cb.metrics.failureCount = 0
	cb.metrics.successCount = 0
}

// Execute runs fn through the circuit breaker. It is used for void operations.
func (cb *CircuitBreaker) Execute(fn func() error) error {
	_, err := ExecuteWithResult(cb, func() (struct{}, error) {
		return struct{}{}, fn()
	})
	return err
}

// ExecuteWithResult runs fn through the circuit breaker and returns the result.
func ExecuteWithResult[T any](cb *CircuitBreaker, fn func() (T, error)) (T, error) {
	var zero T

	if err := cb.preExecute(); err != nil {
		return zero, err
	}

	result, err := fn()

	cb.postExecute(err)
	return result, err
}

func (cb *CircuitBreaker) preExecute() error {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	currentState := cb.loadState()
	switch currentState {
	case StateClosed:
		return nil
	case StateOpen:
		if cb.now().Sub(cb.lastFailureTime) >= cb.config.TimeoutDuration {
			cb.state.Store(StateHalfOpen)
			cb.metrics.successCount = 0
			cb.metrics.failureCount = 0
			return nil
		}
		return ErrCircuitBreakerOpen
	case StateHalfOpen:
		return nil
	default:
		return ErrCircuitBreakerOpen
	}
}

func (cb *CircuitBreaker) postExecute(err error) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	cb.metrics.totalCalls++
	currentState := cb.loadState()

	if err != nil {
		cb.metrics.totalFailures++
		cb.metrics.failureCount++
		cb.lastFailureTime = cb.now()

		switch currentState {
		case StateClosed:
			if cb.metrics.failureCount >= cb.config.FailureThreshold {
				cb.state.Store(StateOpen)
			}
		case StateHalfOpen:
			cb.state.Store(StateOpen)
		case StateOpen:
			// Already open; no transition needed.
		}
	} else {
		switch currentState {
		case StateClosed:
			cb.metrics.failureCount = 0
		case StateHalfOpen:
			cb.metrics.successCount++
			if cb.metrics.successCount >= cb.config.SuccessThreshold {
				cb.state.Store(StateClosed)
				cb.metrics.failureCount = 0
				cb.metrics.successCount = 0
			}
		case StateOpen:
			// Already open; no transition needed.
		}
	}
}
