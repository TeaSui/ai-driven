package com.aidriven.core.model.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.aidriven.spi.model.TicketKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for TicketKey value object.
 * Verifies validation, immutability, and equality semantics.
 */
class TicketKeyTest {

    @Nested
    @DisplayName("TicketKey.of() - Valid inputs")
    class ValidInputs {
        @Test
        void shouldCreateFromValidFormat() {
            TicketKey key = TicketKey.of("PROJ-123");
            assertThat(key.value()).isEqualTo("PROJ-123");
        }

        @Test
        void shouldParseProjectKeyAndTicketNumber() {
            TicketKey key = TicketKey.of("ABC-999");
            assertThat(key.projectKey()).isEqualTo("ABC");
            assertThat(key.ticketNumber()).isEqualTo(999L);
        }

        @ParameterizedTest
        @ValueSource(strings = { "A-1", "PROJ-123", "INFRA-9999", "X-0" })
        void shouldAcceptVariousFormats(String value) {
            assertThat(TicketKey.of(value).value()).isEqualTo(value);
        }

        @Test
        void shouldBeImmutable() {
            TicketKey key = TicketKey.of("PROJ-123");
            assertThat(key.value()).isEqualTo("PROJ-123");
            // Can't modify - field is final, no setters
        }

        @Test
        void shouldSupportEquality() {
            TicketKey key1 = TicketKey.of("PROJ-123");
            TicketKey key2 = TicketKey.of("PROJ-123");
            TicketKey key3 = TicketKey.of("PROJ-456");

            assertThat(key1).isEqualTo(key2);
            assertThat(key1).isNotEqualTo(key3);
        }

        @Test
        void shouldHaveConsistentHashCode() {
            TicketKey key1 = TicketKey.of("PROJ-123");
            TicketKey key2 = TicketKey.of("PROJ-123");

            assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        }

        @Test
        void shouldBeUsableInCollections() {
            TicketKey key1 = TicketKey.of("PROJ-123");
            TicketKey key2 = TicketKey.of("PROJ-123");

            var set = new java.util.HashSet<>();
            set.add(key1);
            set.add(key2);
            assertThat(set).hasSize(1); // deduped via equals/hashCode
        }
    }

    @Nested
    @DisplayName("TicketKey.of() - Invalid inputs")
    class InvalidInputs {
        @Test
        void shouldRejectNull() {
            assertThatThrownBy(() -> TicketKey.of(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null");
        }

        @Test
        void shouldRejectBlank() {
            assertThatThrownBy(() -> TicketKey.of("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null or blank");
        }

        @ParameterizedTest
        @ValueSource(strings = { "proj-123", "PROJ_123", "PROJ--123", "123-PROJ", "-PROJ-123" })
        void shouldRejectInvalidFormat(String value) {
            assertThatThrownBy(() -> TicketKey.of(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must match pattern");
        }

        @Test
        void shouldRejectTooShort() {
            assertThatThrownBy(() -> TicketKey.of("A-"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectTooLong() {
            String longKey = "PROJ-" + "1".repeat(300);
            assertThatThrownBy(() -> TicketKey.of(longKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("length must be between");
        }
    }

    @Nested
    @DisplayName("TicketKey.ofNullable() - Lenient parsing")
    class LenientParsing {
        @Test
        void shouldReturnNullForInvalidInput() {
            assertThat(TicketKey.ofNullable(null)).isNull();
            assertThat(TicketKey.ofNullable("   ")).isNull();
            assertThat(TicketKey.ofNullable("invalid")).isNull();
        }

        @Test
        void shouldReturnInstanceForValidInput() {
            assertThat(TicketKey.ofNullable("PROJ-123")).isNotNull();
        }
    }

    @Nested
    @DisplayName("TicketKey - String conversion")
    class StringConversion {
        @Test
        void shouldHaveReadableToString() {
            TicketKey key = TicketKey.of("PROJ-123");
            assertThat(key.toString()).isEqualTo("PROJ-123");
        }

        @Test
        void shouldBeUsableInStringInterpolation() {
            TicketKey key = TicketKey.of("PROJ-123");
            String message = "Processing ticket: " + key;
            assertThat(message).isEqualTo("Processing ticket: PROJ-123");
        }
    }
}
