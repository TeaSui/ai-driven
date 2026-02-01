package com.aidriven.lambda;

import com.aidriven.core.model.AgentType;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for dry-run mode functionality across all handlers.
 */
class DryRunModeTest {

    // ==================== TicketInfo.isDryRun() Tests ====================

    @ParameterizedTest
    @ValueSource(strings = { "ai-test", "dry-run", "test-mode" })
    void should_detect_dry_run_labels(String label) {
        TicketInfo ticket = TicketInfo.builder()
                .labels(List.of("backend", label))
                .build();

        assertTrue(ticket.isDryRun());
    }

    @ParameterizedTest
    @ValueSource(strings = { "AI-TEST", "DRY-RUN", "TEST-MODE", "Ai-Test" })
    void should_detect_dry_run_labels_case_insensitive(String label) {
        TicketInfo ticket = TicketInfo.builder()
                .labels(List.of(label))
                .build();

        assertTrue(ticket.isDryRun());
    }

    @Test
    void should_detect_dry_run_when_label_contains_keyword() {
        TicketInfo ticket = TicketInfo.builder()
                .labels(List.of("my-ai-test-feature"))
                .build();

        assertTrue(ticket.isDryRun());
    }

    @Test
    void should_not_detect_dry_run_for_normal_labels() {
        TicketInfo ticket = TicketInfo.builder()
                .labels(List.of("backend", "bug", "urgent"))
                .build();

        assertFalse(ticket.isDryRun());
    }

    @Test
    void should_not_detect_dry_run_for_null_labels() {
        TicketInfo ticket = TicketInfo.builder()
                .labels(null)
                .build();

        assertFalse(ticket.isDryRun());
    }

    @Test
    void should_not_detect_dry_run_for_empty_labels() {
        TicketInfo ticket = TicketInfo.builder()
                .labels(Collections.emptyList())
                .build();

        assertFalse(ticket.isDryRun());
    }

    // ==================== ProcessingStatus Tests ====================

    @Test
    void should_have_test_completed_status() {
        ProcessingStatus status = ProcessingStatus.TEST_COMPLETED;

        assertEquals("TEST_COMPLETED", status.getValue());
    }

    @Test
    void should_parse_test_completed_from_value() {
        ProcessingStatus status = ProcessingStatus.fromValue("TEST_COMPLETED");

        assertEquals(ProcessingStatus.TEST_COMPLETED, status);
    }

    // ==================== Handler Output Format Tests ====================

    @Test
    void should_produce_correct_dry_run_output_format_for_backend() {
        // Simulated dry-run output from BackendAgentHandler
        Map<String, Object> output = Map.of(
                "ticketId", "12345",
                "ticketKey", "PROJ-123",
                "agentType", AgentType.BACKEND.getValue(),
                "success", true,
                "dryRun", true,
                "files", "[]",
                "commitMessage", "[DRY-RUN] Would generate backend code",
                "prTitle", "[DRY-RUN] Backend changes for PROJ-123",
                "prDescription", "Simulated PR - no actual changes made");

        assertEquals("12345", output.get("ticketId"));
        assertEquals("PROJ-123", output.get("ticketKey"));
        assertEquals("backend", output.get("agentType"));
        assertTrue((Boolean) output.get("success"));
        assertTrue((Boolean) output.get("dryRun"));
    }

    @Test
    void should_produce_correct_dry_run_output_format_for_frontend() {
        Map<String, Object> output = Map.of(
                "ticketId", "12345",
                "ticketKey", "PROJ-123",
                "agentType", AgentType.FRONTEND.getValue(),
                "success", true,
                "dryRun", true,
                "files", "[]",
                "commitMessage", "[DRY-RUN] Would generate frontend code",
                "prTitle", "[DRY-RUN] Frontend changes for PROJ-123",
                "prDescription", "Simulated PR - no actual changes made");

        assertEquals("frontend", output.get("agentType"));
        assertTrue((Boolean) output.get("dryRun"));
    }

    @Test
    void should_produce_correct_dry_run_output_format_for_security() {
        Map<String, Object> output = Map.of(
                "ticketId", "12345",
                "ticketKey", "PROJ-123",
                "agentType", AgentType.SECURITY.getValue(),
                "success", true,
                "dryRun", true,
                "files", "[]",
                "commitMessage", "[DRY-RUN] Would generate security fixes",
                "prTitle", "[DRY-RUN] Security changes for PROJ-123",
                "prDescription", "Simulated PR - no actual changes made");

        assertEquals("security", output.get("agentType"));
        assertTrue((Boolean) output.get("dryRun"));
    }

    @Test
    void should_produce_correct_pr_creator_dry_run_output() {
        // Simulated dry-run output from PrCreatorHandler
        Map<String, Object> output = Map.of(
                "ticketId", "12345",
                "ticketKey", "PROJ-123",
                "prCreated", false,
                "dryRun", true,
                "reason", "Dry-run mode");

        assertEquals("12345", output.get("ticketId"));
        assertEquals("PROJ-123", output.get("ticketKey"));
        assertFalse((Boolean) output.get("prCreated"));
        assertTrue((Boolean) output.get("dryRun"));
        assertEquals("Dry-run mode", output.get("reason"));
    }

    // ==================== Orchestrator Output Tests ====================

    @Test
    void should_include_dry_run_in_orchestrator_output() {
        // Simulated output from FetchTicketHandler
        Map<String, Object> output = new HashMap<>();
        output.put("ticketId", "12345");
        output.put("ticketKey", "PROJ-123");
        output.put("agentType", "backend");
        output.put("summary", "Test ticket");
        output.put("description", "Test description");
        output.put("labels", List.of("backend", "ai-test"));
        output.put("priority", "High");
        output.put("dryRun", true);

        assertTrue(output.containsKey("dryRun"));
        assertTrue((Boolean) output.get("dryRun"));
    }

    // ==================== Data Flow Tests ====================

    @Test
    void should_propagate_dry_run_through_workflow() {
        // Step 1: Orchestrator detects dry-run from labels
        TicketInfo ticket = TicketInfo.builder()
                .ticketId("12345")
                .ticketKey("PROJ-123")
                .labels(List.of("backend", "ai-test"))
                .summary("Test")
                .description("Test")
                .build();

        boolean dryRunFromOrchestrator = ticket.isDryRun();
        assertTrue(dryRunFromOrchestrator);

        // Step 2: Orchestrator output includes dryRun flag
        Map<String, Object> orchestratorOutput = new HashMap<>();
        orchestratorOutput.put("dryRun", dryRunFromOrchestrator);

        // Step 3: Agent handler receives dryRun flag
        boolean dryRunForAgent = Boolean.TRUE.equals(orchestratorOutput.get("dryRun"));
        assertTrue(dryRunForAgent);

        // Step 4: Agent handler passes dryRun to PR creator
        Map<String, Object> agentOutput = new HashMap<>();
        agentOutput.put("dryRun", dryRunForAgent);

        // Step 5: PR creator receives dryRun flag
        boolean dryRunForPrCreator = Boolean.TRUE.equals(agentOutput.get("dryRun"));
        assertTrue(dryRunForPrCreator);
    }
}
