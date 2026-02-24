package com.aidriven.core.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CircuitBreaker.
 */
class CircuitBreakerTest {

    private CircuitBreaker breaker;

    @BeforeEach
    void setup() {
        breaker = new CircuitBreaker("test-service");
    }

    @Nested
    @DisplayName("CircuitBreaker.CLOSED state")
    class ClosedState {

        @Test
        void shouldExecuteOperationSuccessfully() throws Exception {
            CircuitBreaker.State state = breaker.execute(() -> {
                breaker.getState();
                return CircuitBreaker.State.CLOSED;
            });

            assertThat(state).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.getMetrics().getSuccessCount()).isEqualTo(1);
        }

        @Test
        void shouldRecordFailuresUntilThreshold() throws Exception {
            CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                    .failureThreshold(3)
                    .successThreshold(2)
                    .build();
            CircuitBreaker cb = new CircuitBreaker("test", config);

            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> cb.execute(() -> {
                    throw new RuntimeException("test failure");
                })).isInstanceOf(RuntimeException.class);
            }

            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(cb.getMetrics().getFailureCount()).isEqualTo(2);
        }

        @Test
        void shouldOpenAfterThresholdExceeded() throws Exception {
            CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                    .failureThreshold(3)
                    .successThreshold(2)
                    .build();
            CircuitBreaker cb = new CircuitBreaker("test", config);

            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> cb.execute(() -> {
                    throw new RuntimeException("test failure");
                })).isInstanceOf(RuntimeException.class);
            }

            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("CircuitBreaker.OPEN state")
    class OpenState {

        @Test
        void shouldTransitionToOpen() throws Exception {
            CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                    .failureThreshold(2)
                    .successThreshold(1)
                    .build();
            CircuitBreaker cb = new CircuitBreaker("test", config);

            // Cause failures to reach threshold
            try {
                cb.execute(() -> {
                    throw new RuntimeException("test failure 1");
                });
            } catch (Exception ignored) {
            }

            try {
                cb.execute(() -> {
                    throw new RuntimeException("test failure 2");
                });
            } catch (Exception ignored) {
            }

            // Verify circuit is open
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("CircuitBreaker metrics")
    class Metrics {

        @Test
        void shouldTrackSuccessesAndFailures() throws Exception {
            breaker.execute(() -> "success");
            assertThatThrownBy(() -> breaker.execute(() -> {
                throw new RuntimeException("fail");
            }));

            assertThat(breaker.getMetrics().getSuccessCount()).isEqualTo(1);
            assertThat(breaker.getMetrics().getFailureCount()).isEqualTo(1);
            assertThat(breaker.getMetrics().getFailureRate()).isEqualTo(0.5);
        }
    }
}

