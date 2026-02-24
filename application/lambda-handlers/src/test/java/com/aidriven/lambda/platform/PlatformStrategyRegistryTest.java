package com.aidriven.lambda.platform;

import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.lambda.model.AgentTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlatformStrategyRegistry — registration, retrieval, and error paths.
 */
class PlatformStrategyRegistryTest {

    private PlatformStrategyRegistry registry;

    /** Minimal no-op stub strategy for testing registration behaviour. */
    private static PlatformStrategy stubFor(String platformName) {
        return new PlatformStrategy() {
            @Override public String platform() { return platformName; }

            @Override
            public ProgressTracker createProgressTracker(AgentTask task, SourceControlClient sc) {
                return null;
            }

            @Override
            public void postFinalResponse(AgentTask task, SourceControlClient sc, String formattedResponse) {}
        };
    }

    @BeforeEach
    void setUp() {
        registry = new PlatformStrategyRegistry();
    }

    // ─── Registration & retrieval ───

    @Test
    void should_register_and_retrieve_strategy() {
        PlatformStrategy jira = stubFor("JIRA");
        registry.register(jira);

        assertSame(jira, registry.get("JIRA"));
    }

    @Test
    void should_support_case_insensitive_lookup() {
        registry.register(stubFor("JIRA"));

        assertSame(registry.get("JIRA"), registry.get("jira"));
        assertSame(registry.get("JIRA"), registry.get("Jira"));
    }

    @Test
    void should_register_multiple_strategies_independently() {
        PlatformStrategy jira   = stubFor("JIRA");
        PlatformStrategy github = stubFor("GITHUB");

        registry.register(jira).register(github);

        assertSame(jira,   registry.get("JIRA"));
        assertSame(github, registry.get("GITHUB"));
    }

    // ─── supports() ───

    @Test
    void supports_returns_true_for_registered_platform() {
        registry.register(stubFor("JIRA"));

        assertTrue(registry.supports("JIRA"));
        assertTrue(registry.supports("jira")); // case-insensitive
    }

    @Test
    void supports_returns_false_for_unregistered_platform() {
        assertFalse(registry.supports("GITLAB"));
    }

    @Test
    void supports_returns_false_for_null() {
        assertFalse(registry.supports(null));
    }

    // ─── Error paths ───

    @Test
    void get_throws_IllegalArgumentException_for_unknown_platform() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("NONEXISTENT"));

        assertTrue(ex.getMessage().contains("NONEXISTENT"),
                "Message should contain the unknown platform name");
    }

    @Test
    void get_throws_for_null_platform() {
        assertThrows(IllegalArgumentException.class, () -> registry.get(null));
    }

    @Test
    void get_error_message_lists_registered_platforms() {
        registry.register(stubFor("JIRA"));
        registry.register(stubFor("GITHUB"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("GITLAB"));

        // Operator-friendly error: tell them which platforms ARE registered
        String msg = ex.getMessage();
        assertTrue(msg.contains("JIRA") || msg.contains("GITHUB"),
                "Error message should list registered platforms, got: " + msg);
    }

    // ─── Overwrite semantics ───

    @Test
    void registering_same_platform_twice_replaces_previous() {
        PlatformStrategy first  = stubFor("JIRA");
        PlatformStrategy second = stubFor("JIRA");

        registry.register(first);
        registry.register(second);

        assertSame(second, registry.get("JIRA"),
                "Second registration should overwrite first");
    }
}
