package cache

import (
	"fmt"
	"sync"
	"time"

	gocache "github.com/patrickmn/go-cache"
)

// Cache is a generic in-memory TTL cache backed by patrickmn/go-cache.
// Keys are converted to strings via fmt.Sprintf for internal storage.
type Cache[K comparable, V any] struct {
	store      *gocache.Cache
	defaultTTL time.Duration
	mu         sync.Mutex // protects GetOrCompute
}

// NewCache creates a new Cache with the given default TTL.
// Expired items are cleaned up every 10 minutes.
func NewCache[K comparable, V any](defaultTTL time.Duration) *Cache[K, V] {
	return &Cache[K, V]{
		store:      gocache.New(defaultTTL, 10*time.Minute),
		defaultTTL: defaultTTL,
	}
}

func keyStr[K comparable](key K) string {
	return fmt.Sprintf("%v", key)
}

// Get retrieves a value from the cache. Returns the value and true if found.
func (c *Cache[K, V]) Get(key K) (V, bool) {
	val, found := c.store.Get(keyStr(key))
	if !found {
		var zero V
		return zero, false
	}
	typed, ok := val.(V)
	if !ok {
		var zero V
		return zero, false
	}
	return typed, true
}

// Set stores a value with the default TTL.
func (c *Cache[K, V]) Set(key K, value V) {
	c.store.Set(keyStr(key), value, c.defaultTTL)
}

// SetWithTTL stores a value with a specific TTL.
func (c *Cache[K, V]) SetWithTTL(key K, value V, ttl time.Duration) {
	c.store.Set(keyStr(key), value, ttl)
}

// GetOrCompute retrieves a value from the cache, computing it if absent.
// The computation function is called at most once for a given missing key.
func (c *Cache[K, V]) GetOrCompute(key K, fn func(K) (V, error)) (V, error) {
	if val, ok := c.Get(key); ok {
		return val, nil
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	// Double-check after acquiring lock
	if val, ok := c.Get(key); ok {
		return val, nil
	}

	val, err := fn(key)
	if err != nil {
		var zero V
		return zero, err
	}

	c.Set(key, val)
	return val, nil
}

// Delete removes a key from the cache.
func (c *Cache[K, V]) Delete(key K) {
	c.store.Delete(keyStr(key))
}

// Clear removes all items from the cache.
func (c *Cache[K, V]) Clear() {
	c.store.Flush()
}

// Size returns the number of items in the cache (including expired but not yet cleaned up).
func (c *Cache[K, V]) Size() int {
	return c.store.ItemCount()
}
