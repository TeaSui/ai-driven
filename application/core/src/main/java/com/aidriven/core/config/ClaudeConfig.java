package com.aidriven.core.config;

/**
 * Configuration for Claude AI model interactions.
 */
public record ClaudeConfig(
        int maxContext,
        String model,
        int maxTokens,
        double temperature,
        String promptVersion,
        String secretArn,
        String fallbackModel,
        String researcherModel,
        int researcherMaxTokens,
        String provider,
        String bedrockRegion) {

    private static final String DEFAULT_PROVIDER = "ANTHROPIC_API";
    private static final String DEFAULT_BEDROCK_REGION = "us-east-1";

    public String provider() {
        return provider != null && !provider.isBlank() ? provider : DEFAULT_PROVIDER;
    }

    public String bedrockRegion() {
        return bedrockRegion != null && !bedrockRegion.isBlank() ? bedrockRegion : DEFAULT_BEDROCK_REGION;
    }
}
