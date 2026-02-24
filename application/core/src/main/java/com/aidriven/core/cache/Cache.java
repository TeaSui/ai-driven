package com.aidriven.core.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Generic cache interface for flexible implementation strategies.
 * Supports TTL-based expiration and compute-if-absent patterns.
 *
 * @param <K> Key type
 * @param <V> Value type
 *
 * @since 1.0
 */
public interface Cache<K, V> {

    /**
     * Gets a value from cache.
     *
     * @param key Cache key
     * @return Value if present and not expired, empty otherwise
     */
    Optional<V> get(K key);

    /**
     * Puts a value in cache with default TTL.
     *
     * @param key Cache key
     * @param value Value to cache
     */
    void put(K key, V value);

    /**
     * Puts a value in cache with custom TTL.
     *
     * @param key Cache key
     * @param value Value to cache
     * @param ttl Time-to-live duration
     */
    void put(K key, V value, Duration ttl);

    /**
     * Computes and caches value if absent.
     * Useful for single-threaded check-then-act pattern.
     *
     * @param key Cache key
     * @param supplier Supplies value if not cached
     * @return Cached or computed value
     * @throws Exception if supplier throws
     */
    V computeIfAbsent(K key, CacheSupplier<K, V> supplier) throws Exception;

    /**
     * Removes a value from cache.
     *
     * @param key Cache key
     */
    void remove(K key);

    /**
     * Clears all entries from cache.
     */
    void clear();

    /**
     * Gets current cache size.
     *
     * @return number of entries
     */
    int size();

    /**
     * Gets cache statistics.
     *
     * @return stats
     */
    CacheStats getStats();

    /**
     * Supplier for compute-if-absent operations.
     *
     * @param <K> Key type
     * @param <V> Value type
     */
    @FunctionalInterface
    interface CacheSupplier<K, V> {
        V supply(K key) throws Exception;
    }

    /**
     * Cache statistics.
     */
    record CacheStats(
            long hits,           // Number of cache hits
            long misses,         // Number of cache misses
            long size,           // Current cache size
            Instant lastAccess   // Time of last access
    ) {
        /**
         * Calculates hit ratio.
         *
         * @return hit ratio (0.0 to 1.0)
         */
        public double getHitRatio() {
            long total = hits + misses;
            if (total == 0) {
                return 0.0;
            }
            return (double) hits / total;
        }
    }
}

