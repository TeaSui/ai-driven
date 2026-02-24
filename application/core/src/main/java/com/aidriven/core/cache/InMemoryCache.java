package com.aidriven.core.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * In-memory cache implementation with TTL support.
 * Thread-safe using read-write locks for optimal concurrent performance.
 *
 * <p>
 * Features:
 * - TTL-based expiration
 * - Compute-if-absent for atomic operations
 * - Hit/miss tracking for monitoring
 * - Thread-safe with read-write locks
 *
 * @param <K> Key type
 * @param <V> Value type
 *
 * @since 1.0
 */
public class InMemoryCache<K, V> implements Cache<K, V> {

    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    @Getter
    @AllArgsConstructor
    private static class CacheEntry<V> {
        V value;
        Instant expiresAt;

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<K, CacheEntry<V>> entries = new HashMap<>();
    private final Duration defaultTtl;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private long hits = 0;
    private long misses = 0;
    private Instant lastAccess = Instant.now();

    /**
     * Creates cache with default TTL (1 hour).
     */
    public InMemoryCache() {
        this(DEFAULT_TTL);
    }

    /**
     * Creates cache with custom default TTL.
     *
     * @param defaultTtl Time-to-live for entries
     */
    public InMemoryCache(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    @Override
    public Optional<V> get(K key) {
        lock.readLock().lock();
        try {
            CacheEntry<V> entry = entries.get(key);

            if (entry == null || entry.isExpired()) {
                misses++;
                lastAccess = Instant.now();

                // If expired, remove it (upgrade to write lock)
                if (entry != null && entry.isExpired()) {
                    lock.readLock().unlock();
                    lock.writeLock().lock();
                    try {
                        entries.remove(key);
                    } finally {
                        lock.writeLock().unlock();
                        lock.readLock().lock();
                    }
                }

                return Optional.empty();
            }

            hits++;
            lastAccess = Instant.now();
            return Optional.of(entry.value);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        put(key, value, defaultTtl);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        lock.writeLock().lock();
        try {
            Instant expiresAt = Instant.now().plus(ttl);
            entries.put(key, new CacheEntry<>(value, expiresAt));
            lastAccess = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public V computeIfAbsent(K key, Cache.CacheSupplier<K, V> supplier) throws Exception {
        // Check if exists (read lock)
        lock.readLock().lock();
        try {
            Optional<V> cached = get(key);
            if (cached.isPresent()) {
                return cached.get();
            }
        } finally {
            lock.readLock().unlock();
        }

        // Compute and cache (write lock)
        lock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            Optional<V> cached = get(key);
            if (cached.isPresent()) {
                return cached.get();
            }

            V value = supplier.supply(key);
            put(key, value);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(K key) {
        lock.writeLock().lock();
        try {
            entries.remove(key);
            lastAccess = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
            hits = 0;
            misses = 0;
            lastAccess = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Cache.CacheStats getStats() {
        lock.readLock().lock();
        try {
            return new Cache.CacheStats(hits, misses, entries.size(), lastAccess);
        } finally {
            lock.readLock().unlock();
        }
    }
}

