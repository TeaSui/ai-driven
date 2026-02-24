package com.aidriven.core.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelSelectorTest {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    @Test
    void shouldReturnDefaultWhenLabelsNull() {
        assertEquals(DEFAULT_MODEL, ModelSelector.resolve(null, DEFAULT_MODEL));
    }

    @Test
    void shouldReturnDefaultWhenLabelsEmpty() {
        assertEquals(DEFAULT_MODEL, ModelSelector.resolve(Collections.emptyList(), DEFAULT_MODEL));
    }

    @Test
    void shouldResolveHaikuLabel() {
        assertEquals("claude-haiku-4-5",
                ModelSelector.resolve(List.of("ai-model:haiku"), DEFAULT_MODEL));
    }

    @Test
    void shouldResolveSonnetLabel() {
        assertEquals("claude-sonnet-4-6",
                ModelSelector.resolve(List.of("ai-model:sonnet"), DEFAULT_MODEL));
    }

    @Test
    void shouldResolveOpusLabel() {
        // ADR-012: Opus version must match README (claude-opus-4-6, not 4-5)
        assertEquals("claude-opus-4-6",
                ModelSelector.resolve(List.of("ai-model:opus"), DEFAULT_MODEL));
    }

    @Test
    void shouldIgnoreUnrelatedLabelsAndReturnDefault() {
        assertEquals(DEFAULT_MODEL,
                ModelSelector.resolve(List.of("backend", "high-priority"), DEFAULT_MODEL));
    }

    @Test
    void shouldUseFirsMatchingLabel() {
        assertEquals("claude-haiku-4-5",
                ModelSelector.resolve(List.of("ai-model:haiku", "ai-model:opus"), DEFAULT_MODEL));
    }

    @Test
    void shouldBeCaseInsensitive() {
        assertEquals("claude-sonnet-4-6",
                ModelSelector.resolve(List.of("AI-MODEL:SONNET"), DEFAULT_MODEL));
    }

    @Test
    void shouldIgnoreUnknownTier() {
        assertEquals(DEFAULT_MODEL,
                ModelSelector.resolve(List.of("ai-model:unknown-tier"), DEFAULT_MODEL));
    }

    @Test
    void shouldReturnNullDefaultWhenNoMatchAndDefaultIsNull() {
        assertNull(ModelSelector.resolve(List.of("backend"), null));
    }

    @Test
    void shouldReturnSupportedTiers() {
        var tiers = ModelSelector.supportedTiers();
        assertTrue(tiers.contains("haiku"));
        assertTrue(tiers.contains("sonnet"));
        assertTrue(tiers.contains("opus"));
        assertEquals(3, tiers.size());
    }
}
