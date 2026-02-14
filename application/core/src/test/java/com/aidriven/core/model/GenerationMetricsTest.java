package com.aidriven.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenerationMetricsTest {

    @Test
    void shouldCreateMetricsWithFactoryMethod() {
        GenerationMetrics metrics = GenerationMetrics.forGeneration(
                "PROJ-123", "claude-opus-4-6", "v1",
                5000, 2000, 3, List.of("backend", "ai-model:opus"));

        assertEquals("METRICS#PROJ-123", metrics.getPk());
        assertTrue(metrics.getSk().startsWith("GEN#"));
        assertEquals("PROJ-123", metrics.getTicketKey());
        assertEquals("claude-opus-4-6", metrics.getModel());
        assertEquals("v1", metrics.getPromptVersion());
        assertEquals(5000, metrics.getInputTokens());
        assertEquals(2000, metrics.getOutputTokens());
        assertEquals(3, metrics.getFilesGenerated());
        assertEquals(2, metrics.getTicketLabels().size());
        assertNotNull(metrics.getCreatedAt());
        assertNull(metrics.getPrApproved());
        assertNull(metrics.getTimeToApprovalSeconds());
    }

    @Test
    void shouldMarkAsApproved() {
        Instant past = Instant.now().minusSeconds(3600); // 1 hour ago
        GenerationMetrics metrics = GenerationMetrics.builder()
                .pk("METRICS#PROJ-123")
                .sk("GEN#" + past)
                .ticketKey("PROJ-123")
                .createdAt(past)
                .build();

        GenerationMetrics approved = metrics.withApprovalResult(true);

        assertTrue(approved.getPrApproved());
        assertNotNull(approved.getTimeToApprovalSeconds());
        // Time to approval should be approximately 3600 seconds (1 hour)
        assertTrue(approved.getTimeToApprovalSeconds() >= 3599);
    }

    @Test
    void shouldMarkAsRejected() {
        GenerationMetrics metrics = GenerationMetrics.builder()
                .pk("METRICS#PROJ-456")
                .sk("GEN#2024-01-01T00:00:00Z")
                .ticketKey("PROJ-456")
                .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
                .build();

        GenerationMetrics rejected = metrics.withApprovalResult(false);

        assertFalse(rejected.getPrApproved());
        assertNull(rejected.getTimeToApprovalSeconds());
    }

    @Test
    void shouldHandleNullCreatedAtInApproval() {
        GenerationMetrics metrics = GenerationMetrics.builder()
                .pk("METRICS#PROJ-789")
                .sk("GEN#x")
                .ticketKey("PROJ-789")
                .build();

        GenerationMetrics approved = metrics.withApprovalResult(true);

        assertTrue(approved.getPrApproved());
        assertNull(approved.getTimeToApprovalSeconds());
    }

    @Test
    void shouldPreserveAllFieldsOnApproval() {
        GenerationMetrics original = GenerationMetrics.forGeneration(
                "PROJ-100", "claude-sonnet-4-5", "v2",
                1000, 500, 2, List.of("frontend"));

        GenerationMetrics approved = original.withApprovalResult(true);

        assertEquals(original.getModel(), approved.getModel());
        assertEquals(original.getPromptVersion(), approved.getPromptVersion());
        assertEquals(original.getInputTokens(), approved.getInputTokens());
        assertEquals(original.getOutputTokens(), approved.getOutputTokens());
        assertEquals(original.getFilesGenerated(), approved.getFilesGenerated());
        assertEquals(original.getTicketLabels(), approved.getTicketLabels());
    }
}
