package resilience

import (
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

var errTest = errors.New("test error")

func TestExecuteClosedStateSuccess(t *testing.T) {
	cb := New("test", DefaultConfig())

	err := cb.Execute(func() error {
		return nil
	})

	assert.NoError(t, err)
	assert.Equal(t, StateClosed, cb.State())
}

func TestExecuteClosedStateResetsFailureCountOnSuccess(t *testing.T) {
	cb := New("test", DefaultConfig())

	// Accumulate some failures (below threshold)
	for i := 0; i < 3; i++ {
		_ = cb.Execute(func() error { return errTest })
	}
	assert.Equal(t, StateClosed, cb.State())

	// Success should reset failure count
	_ = cb.Execute(func() error { return nil })

	// Now another 3 failures should not trip the breaker (threshold is 5)
	for i := 0; i < 3; i++ {
		_ = cb.Execute(func() error { return errTest })
	}
	assert.Equal(t, StateClosed, cb.State())
}

func TestTransitionToOpenAfterThresholdFailures(t *testing.T) {
	cfg := Config{FailureThreshold: 3, SuccessThreshold: 1, TimeoutDuration: 30 * time.Second}
	cb := New("test", cfg)

	for i := 0; i < 3; i++ {
		_ = cb.Execute(func() error { return errTest })
	}

	assert.Equal(t, StateOpen, cb.State())
}

func TestOpenStateRejectsCalls(t *testing.T) {
	cfg := Config{FailureThreshold: 1, SuccessThreshold: 1, TimeoutDuration: 30 * time.Second}
	cb := New("test", cfg)

	_ = cb.Execute(func() error { return errTest })
	assert.Equal(t, StateOpen, cb.State())

	err := cb.Execute(func() error { return nil })
	assert.ErrorIs(t, err, ErrCircuitBreakerOpen)
}

func TestTransitionToHalfOpenAfterTimeout(t *testing.T) {
	cfg := Config{FailureThreshold: 1, SuccessThreshold: 1, TimeoutDuration: 100 * time.Millisecond}
	cb := New("test", cfg)

	now := time.Now()
	cb.now = func() time.Time { return now }

	_ = cb.Execute(func() error { return errTest })
	assert.Equal(t, StateOpen, cb.State())

	// Advance time past timeout
	cb.now = func() time.Time { return now.Add(200 * time.Millisecond) }

	err := cb.Execute(func() error { return nil })
	assert.NoError(t, err)
	assert.Equal(t, StateClosed, cb.State()) // success in half-open with threshold=1 → closed
}

func TestHalfOpenToClosedOnSuccessThreshold(t *testing.T) {
	cfg := Config{FailureThreshold: 1, SuccessThreshold: 3, TimeoutDuration: 100 * time.Millisecond}
	cb := New("test", cfg)

	now := time.Now()
	cb.now = func() time.Time { return now }

	_ = cb.Execute(func() error { return errTest })
	assert.Equal(t, StateOpen, cb.State())

	// Advance time past timeout
	cb.now = func() time.Time { return now.Add(200 * time.Millisecond) }

	// First success: transitions to half-open, stays half-open
	_ = cb.Execute(func() error { return nil })
	assert.Equal(t, StateHalfOpen, cb.State())

	// Second success
	_ = cb.Execute(func() error { return nil })
	assert.Equal(t, StateHalfOpen, cb.State())

	// Third success: reaches threshold → closed
	_ = cb.Execute(func() error { return nil })
	assert.Equal(t, StateClosed, cb.State())
}

func TestHalfOpenToOpenOnFailure(t *testing.T) {
	cfg := Config{FailureThreshold: 1, SuccessThreshold: 3, TimeoutDuration: 100 * time.Millisecond}
	cb := New("test", cfg)

	now := time.Now()
	cb.now = func() time.Time { return now }

	_ = cb.Execute(func() error { return errTest })
	assert.Equal(t, StateOpen, cb.State())

	// Advance time past timeout
	cb.now = func() time.Time { return now.Add(200 * time.Millisecond) }

	// Success transitions to half-open
	_ = cb.Execute(func() error { return nil })
	assert.Equal(t, StateHalfOpen, cb.State())

	// Failure in half-open → open
	_ = cb.Execute(func() error { return errTest })
	assert.Equal(t, StateOpen, cb.State())
}

func TestReset(t *testing.T) {
	cfg := Config{FailureThreshold: 1, SuccessThreshold: 1, TimeoutDuration: 30 * time.Second}
	cb := New("test", cfg)

	_ = cb.Execute(func() error { return errTest })
	assert.Equal(t, StateOpen, cb.State())

	cb.Reset()
	assert.Equal(t, StateClosed, cb.State())

	// Should accept calls again
	err := cb.Execute(func() error { return nil })
	assert.NoError(t, err)
}

func TestExecuteWithResultSuccess(t *testing.T) {
	cb := New("test", DefaultConfig())

	result, err := ExecuteWithResult(cb, func() (int, error) {
		return 42, nil
	})

	require.NoError(t, err)
	assert.Equal(t, 42, result)
}

func TestExecuteWithResultOpenState(t *testing.T) {
	cfg := Config{FailureThreshold: 1, SuccessThreshold: 1, TimeoutDuration: 30 * time.Second}
	cb := New("test", cfg)

	_ = cb.Execute(func() error { return errTest })

	result, err := ExecuteWithResult(cb, func() (string, error) {
		return "value", nil
	})

	assert.ErrorIs(t, err, ErrCircuitBreakerOpen)
	assert.Equal(t, "", result)
}

func TestConcurrentAccessSafety(t *testing.T) {
	cfg := Config{FailureThreshold: 50, SuccessThreshold: 5, TimeoutDuration: 1 * time.Second}
	cb := New("test", cfg)

	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if i%2 == 0 {
				_ = cb.Execute(func() error { return nil })
			} else {
				_ = cb.Execute(func() error { return errTest })
			}
			_ = cb.State()
		}(i)
	}
	wg.Wait()

	// Should not panic or deadlock; state is valid
	state := cb.State()
	assert.Contains(t, []State{StateClosed, StateOpen, StateHalfOpen}, state)
}

func TestDefaultConfigs(t *testing.T) {
	def := DefaultConfig()
	assert.Equal(t, 5, def.FailureThreshold)
	assert.Equal(t, 2, def.SuccessThreshold)
	assert.Equal(t, 30*time.Second, def.TimeoutDuration)

	agg := AggressiveConfig()
	assert.Equal(t, 3, agg.FailureThreshold)
	assert.Equal(t, 1, agg.SuccessThreshold)
	assert.Equal(t, 10*time.Second, agg.TimeoutDuration)

	lenCfg := LenientConfig()
	assert.Equal(t, 10, lenCfg.FailureThreshold)
	assert.Equal(t, 5, lenCfg.SuccessThreshold)
	assert.Equal(t, 60*time.Second, lenCfg.TimeoutDuration)
}

func TestStateString(t *testing.T) {
	assert.Equal(t, "CLOSED", StateClosed.String())
	assert.Equal(t, "OPEN", StateOpen.String())
	assert.Equal(t, "HALF_OPEN", StateHalfOpen.String())
}
