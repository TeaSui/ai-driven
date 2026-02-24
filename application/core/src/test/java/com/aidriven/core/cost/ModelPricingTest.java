package com.aidriven.core.cost;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelPricingTest {

    @Test
    void should_return_zero_cost_for_zero_tokens() {
        double cost = ModelPricing.estimateCostUsd("claude-opus-4-6", 0, 0);
        assertEquals(0.0, cost, 0.0001);
    }

    @Test
    void should_compute_cost_correctly_for_opus() {
        // claude-opus-4-6: $15/M input, $75/M output
        // 1M input + 1M output = $15 + $75 = $90
        double cost = ModelPricing.estimateCostUsd("claude-opus-4-6", 1_000_000, 1_000_000);
        assertEquals(90.0, cost, 0.01);
    }

    @Test
    void should_compute_cost_correctly_for_sonnet() {
        // claude-sonnet-4-6: $3/M input, $15/M output
        // 500k input + 200k output = $1.50 + $3.00 = $4.50 / 2 = ... let's use
        // absolute:
        // (500_000 / 1_000_000) * 3 + (200_000 / 1_000_000) * 15 = 1.5 + 3.0 = 4.5
        double cost = ModelPricing.estimateCostUsd("claude-sonnet-4-6", 500_000, 200_000);
        assertEquals(4.5, cost, 0.01);
    }

    @Test
    void should_compute_cost_correctly_for_haiku() {
        // claude-haiku-4-6: $0.80/M input, $4/M output
        // 1M input + 0.5M output = $0.80 + $2.00 = $2.80
        double cost = ModelPricing.estimateCostUsd("claude-haiku-4-6", 1_000_000, 500_000);
        assertEquals(2.80, cost, 0.01);
    }

    @Test
    void should_fall_back_to_sonnet_pricing_for_unknown_model() {
        // Unknown model → defaults to Sonnet pricing
        double opusCost = ModelPricing.estimateCostUsd("unknown-model-xyz", 1_000_000, 1_000_000);
        double sonnetDefault = ModelPricing.estimateCostUsd("claude-sonnet-4-6", 1_000_000, 1_000_000);
        assertEquals(sonnetDefault, opusCost, 0.01);
    }

    @Test
    void should_estimate_tokens_from_char_length() {
        // 3500 chars should estimate ~1000 tokens (/ 3.5)
        int tokens = ModelPricing.estimateInputTokens(3500);
        assertEquals(1000, tokens);
    }

    @Test
    void should_estimate_tokens_as_ceiling() {
        // 1 char → 1 token (ceiling at minimum)
        int tokens = ModelPricing.estimateInputTokens(1);
        assertEquals(1, tokens);
    }

    @Test
    void should_return_true_when_exceeds_80_percent_threshold() {
        // If estimated tokens = 180k and limit = 200k → 180k/200k = 90% > 80%, so
        // should trigger
        assertTrue(ModelPricing.exceedsThreshold(180_000, 200_000, 0.80));
    }

    @Test
    void should_return_false_when_below_80_percent_threshold() {
        // 150k/200k = 75% < 80%, should not trigger
        assertFalse(ModelPricing.exceedsThreshold(150_000, 200_000, 0.80));
    }
}
