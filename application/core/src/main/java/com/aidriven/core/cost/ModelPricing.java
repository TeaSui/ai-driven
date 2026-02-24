package com.aidriven.core.cost;

import java.util.Map;

/**
 * Provides Claude model pricing information and token estimation utilities.
 *
 * <p>
 * Pricing reference (USD per 1M tokens, as of early 2026):
 * - claude-opus-4-6: $15 input / $75 output
 * - claude-sonnet-4-6: $3 input / $15 output
 * - claude-haiku-4-6: $0.80 input / $4 output
 */
public final class ModelPricing {

    // Pricing per 1M tokens in USD
    private static final Map<String, double[]> PRICING = Map.of(
            "claude-opus-4-6", new double[] { 15.0, 75.0 },
            "claude-3-opus-20240229", new double[] { 15.0, 75.0 },
            "claude-sonnet-4-6", new double[] { 3.0, 15.0 },
            "claude-3-5-sonnet-20241022", new double[] { 3.0, 15.0 },
            "claude-3-5-sonnet-20240620", new double[] { 3.0, 15.0 },
            "claude-haiku-4-6", new double[] { 0.80, 4.0 },
            "claude-3-haiku-20240307", new double[] { 0.25, 1.25 });

    // Default fallback (Sonnet pricing)
    private static final double[] DEFAULT_PRICING = new double[] { 3.0, 15.0 };

    /**
     * Characters per token divisor — conservative (3.5 gives ~80% headroom for
     * non-ASCII).
     */
    public static final double CHARS_PER_TOKEN = 3.5;

    private ModelPricing() {
    }

    /**
     * Estimates the USD cost of a Claude API call.
     *
     * @param modelId      Claude model identifier
     * @param inputTokens  Number of input tokens
     * @param outputTokens Number of output tokens
     * @return Estimated cost in USD
     */
    public static double estimateCostUsd(String modelId, int inputTokens, int outputTokens) {
        double[] prices = resolve(modelId);
        return (inputTokens / 1_000_000.0) * prices[0]
                + (outputTokens / 1_000_000.0) * prices[1];
    }

    /**
     * Estimates token count from a prompt's character length.
     * Uses chars/3.5 with a ceiling to stay conservative.
     *
     * @param charLength Number of characters in the prompt
     * @return Estimated token count (minimum 1)
     */
    public static int estimateInputTokens(int charLength) {
        return Math.max(1, (int) Math.ceil(charLength / CHARS_PER_TOKEN));
    }

    /**
     * Returns true if the estimated token count exceeds {@code thresholdFraction}
     * of the allowed maximum. For example, {@code threshold=0.80} triggers when
     * estimated tokens >= 80% of the limit.
     *
     * @param estimatedTokens Estimated token count
     * @param maxTokens       The hard limit
     * @param threshold       Fractional threshold (0.0–1.0)
     * @return true if the threshold is exceeded
     */
    public static boolean exceedsThreshold(int estimatedTokens, int maxTokens, double threshold) {
        return estimatedTokens >= maxTokens * threshold;
    }

    private static double[] resolve(String modelId) {
        if (modelId == null)
            return DEFAULT_PRICING;
        // Exact match first
        if (PRICING.containsKey(modelId))
            return PRICING.get(modelId);
        // Prefix match (e.g. handles variant suffixes gracefully)
        for (Map.Entry<String, double[]> entry : PRICING.entrySet()) {
            if (modelId.startsWith(entry.getKey().split("-\\d")[0])) {
                return entry.getValue();
            }
        }
        return DEFAULT_PRICING;
    }
}
