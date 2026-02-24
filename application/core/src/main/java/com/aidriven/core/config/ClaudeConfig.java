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
                String fallbackModel) {
}
