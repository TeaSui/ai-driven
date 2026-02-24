package com.aidriven.core.cache;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for InMemoryCache.
 */
class InMemoryCacheTest {

    private InMemoryCache<String, String> cache;

    @BeforeEach
    void setup() {
        cache = new InMemoryCache<>(Duration.ofSeconds(10));
    }

    @Nested
    @DisplayName("InMemoryCache.put/get")
    class PutGet {

        @Test
        void shouldStorAndRetrieveValue() {
            cache.put("key1", "value1");
            Optional<String> result = cache.get("key1");

            assertThat(result).isPresent().contains("value1");
        }

        @Test
        void shouldReturnEmptyForMissingKey() {
            Optional<String> result = cache.get("nonexistent");
            assertThat(result).isEmpty();
        }

        @Test
        void shouldTrackHitsAndMisses() {
            cache.put("key1", "value1");

            cache.get("key1");  // hit
            cache.get("key1");  // hit
            cache.get("missing");  // miss

            Cache.CacheStats stats = cache.getStats();
            assertThat(stats.hits()).isEqualTo(2);
            assertThat(stats.misses()).isEqualTo(1);
            assertThat(stats.getHitRatio()).isEqualTo(2.0 / 3);
        }
    }

    @Nested
    @DisplayName("InMemoryCache.computeIfAbsent")
    class ComputeIfAbsent {

        @Test
        void shouldComputeIfAbsent() throws Exception {
            String result = cache.computeIfAbsent("key1", key -> "computed:" + key);

            assertThat(result).isEqualTo("computed:key1");
            assertThat(cache.get("key1")).contains("computed:key1");
        }

        @Test
        void shouldUsesCachedValueIfPresent() throws Exception {
            cache.put("key1", "cached");

            String result = cache.computeIfAbsent("key1", key -> "computed:" + key);

            assertThat(result).isEqualTo("cached");
        }
    }

    @Nested
    @DisplayName("InMemoryCache.remove/clear")
    class RemoveClear {

        @Test
        void shouldRemoveEntry() {
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            cache.remove("key1");

            assertThat(cache.get("key1")).isEmpty();
            assertThat(cache.get("key2")).isPresent();
        }

        @Test
        void shouldClearAllEntries() {
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            cache.clear();

            assertThat(cache.size()).isEqualTo(0);
            assertThat(cache.getStats().hits()).isEqualTo(0);
            assertThat(cache.getStats().misses()).isEqualTo(0);
        }
    }
}

