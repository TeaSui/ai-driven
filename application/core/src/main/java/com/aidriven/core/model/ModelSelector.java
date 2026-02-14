package com.aidriven.core.model;

import java.util.List;
import java.util.Map;

/**
 * Resolves the Claude model to use based on Jira ticket labels.
 *
 * <p>
 * Labels follow the format {@code ai-model:<tier>} where tier is one of:
 * haiku, sonnet, opus. If no matching label is found, the supplied default
 * model is returned.
 * </p>
 */
public final class ModelSelector {

    private static final String LABEL_PREFIX = "ai-model:";

    private static final Map<String, String> MODEL_MAP = Map.of(
            "haiku", "claude-haiku-4-5",
            "sonnet", "claude-sonnet-4-5",
            "opus", "claude-opus-4-6");

    private ModelSelector() {
    }

    /**
     * Resolves the Claude model ID from a list of Jira labels.
     *
     * @param labels       the ticket labels (may be null or empty)
     * @param defaultModel the fallback model ID when no label matches
     * @return the resolved Anthropic model ID
     */
    public static String resolve(List<String> labels, String defaultModel) {
        if (labels == null || labels.isEmpty()) {
            return defaultModel;
        }
        for (String label : labels) {
            String lower = label.toLowerCase().trim();
            if (lower.startsWith(LABEL_PREFIX)) {
                String tier = lower.substring(LABEL_PREFIX.length());
                String modelId = MODEL_MAP.get(tier);
                if (modelId != null) {
                    return modelId;
                }
            }
        }
        return defaultModel;
    }

    /**
     * Returns the set of recognised tier names (haiku, sonnet, opus).
     */
    public static java.util.Set<String> supportedTiers() {
        return MODEL_MAP.keySet();
    }
}
