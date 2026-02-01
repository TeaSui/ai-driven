package com.aidriven.core.model;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class TicketInfoTest {

    @Test
    public void testGetAgentTypeFromLabels() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("backend", "bug"))
                .description("Fix the API endpoint")
                .build();

        assertEquals(AgentType.BACKEND, info.determineAgentType());
    }

    @Test
    public void testGetAgentTypeFromDescription() {
        TicketInfo info = TicketInfo.builder()
                .labels(Collections.emptyList())
                .description("Change the button color of the frontend")
                .build();

        assertEquals(AgentType.FRONTEND, info.determineAgentType());
    }

    @Test
    public void testDefaultAgentType() {
        TicketInfo info = TicketInfo.builder()
                .labels(Collections.emptyList())
                .description("Random task")
                .build();

        assertEquals(AgentType.BACKEND, info.determineAgentType());
    }

    @Test
    public void testSecurityAgentType() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("security"))
                .description("Fix vulnerability")
                .build();

        assertEquals(AgentType.SECURITY, info.determineAgentType());
    }

    @Test
    void should_return_true_when_label_is_ai_test() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("backend", "ai-test"))
                .build();

        assertTrue(info.isDryRun());
    }

    @Test
    void should_return_true_when_label_is_dry_run() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("dry-run"))
                .build();

        assertTrue(info.isDryRun());
    }

    @Test
    void should_return_true_when_label_is_test_mode() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("test-mode", "frontend"))
                .build();

        assertTrue(info.isDryRun());
    }

    @Test
    void should_return_true_when_label_contains_dry_run_keyword() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("my-ai-test-label"))
                .build();

        assertTrue(info.isDryRun());
    }

    @Test
    void should_return_true_when_label_is_uppercase() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("AI-TEST"))
                .build();

        assertTrue(info.isDryRun());
    }

    @Test
    void should_return_false_when_labels_is_null() {
        TicketInfo info = TicketInfo.builder()
                .labels(null)
                .build();

        assertFalse(info.isDryRun());
    }

    @Test
    void should_return_false_when_labels_is_empty() {
        TicketInfo info = TicketInfo.builder()
                .labels(Collections.emptyList())
                .build();

        assertFalse(info.isDryRun());
    }

    @Test
    void should_return_false_when_no_dry_run_label() {
        TicketInfo info = TicketInfo.builder()
                .labels(List.of("backend", "bug", "priority-high"))
                .build();

        assertFalse(info.isDryRun());
    }
}
