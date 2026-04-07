package cache

import (
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCache_SetAndGet(t *testing.T) {
	c := NewCache[string, string](5 * time.Minute)

	c.Set("key1", "value1")
	val, ok := c.Get("key1")
	assert.True(t, ok)
	assert.Equal(t, "value1", val)
}

func TestCache_GetMissing(t *testing.T) {
	c := NewCache[string, int](5 * time.Minute)

	val, ok := c.Get("missing")
	assert.False(t, ok)
	assert.Zero(t, val)
}

func TestCache_SetWithTTL(t *testing.T) {
	c := NewCache[string, string](5 * time.Minute)

	c.SetWithTTL("short", "value", 50*time.Millisecond)
	val, ok := c.Get("short")
	assert.True(t, ok)
	assert.Equal(t, "value", val)

	time.Sleep(100 * time.Millisecond)
	_, ok = c.Get("short")
	assert.False(t, ok)
}

func TestCache_TTLExpiry(t *testing.T) {
	c := NewCache[string, string](50 * time.Millisecond)

	c.Set("key", "value")
	val, ok := c.Get("key")
	assert.True(t, ok)
	assert.Equal(t, "value", val)

	time.Sleep(100 * time.Millisecond)
	_, ok = c.Get("key")
	assert.False(t, ok)
}

func TestCache_Delete(t *testing.T) {
	c := NewCache[string, string](5 * time.Minute)

	c.Set("key", "value")
	c.Delete("key")
	_, ok := c.Get("key")
	assert.False(t, ok)
}

func TestCache_Clear(t *testing.T) {
	c := NewCache[string, string](5 * time.Minute)

	c.Set("a", "1")
	c.Set("b", "2")
	assert.Equal(t, 2, c.Size())

	c.Clear()
	assert.Equal(t, 0, c.Size())
}

func TestCache_Size(t *testing.T) {
	c := NewCache[int, string](5 * time.Minute)

	assert.Equal(t, 0, c.Size())
	c.Set(1, "one")
	c.Set(2, "two")
	assert.Equal(t, 2, c.Size())
}

func TestCache_GetOrCompute_Hit(t *testing.T) {
	c := NewCache[string, int](5 * time.Minute)
	c.Set("cached", 42)

	calls := 0
	val, err := c.GetOrCompute("cached", func(k string) (int, error) {
		calls++
		return 99, nil
	})

	require.NoError(t, err)
	assert.Equal(t, 42, val)
	assert.Equal(t, 0, calls, "compute function should not be called on cache hit")
}

func TestCache_GetOrCompute_Miss(t *testing.T) {
	c := NewCache[string, int](5 * time.Minute)

	val, err := c.GetOrCompute("new", func(k string) (int, error) {
		return 99, nil
	})

	require.NoError(t, err)
	assert.Equal(t, 99, val)

	// Should now be cached
	cached, ok := c.Get("new")
	assert.True(t, ok)
	assert.Equal(t, 99, cached)
}

func TestCache_GetOrCompute_Error(t *testing.T) {
	c := NewCache[string, int](5 * time.Minute)

	expectedErr := errors.New("computation failed")
	_, err := c.GetOrCompute("fail", func(k string) (int, error) {
		return 0, expectedErr
	})

	assert.ErrorIs(t, err, expectedErr)

	// Should not be cached on error
	_, ok := c.Get("fail")
	assert.False(t, ok)
}

func TestCache_IntKeys(t *testing.T) {
	c := NewCache[int, string](5 * time.Minute)

	c.Set(42, "answer")
	val, ok := c.Get(42)
	assert.True(t, ok)
	assert.Equal(t, "answer", val)
}
