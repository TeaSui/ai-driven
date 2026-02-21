package com.aidriven.core.plugin;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorTest {

    @Test
    void should_create_valid_descriptor() {
        PluginDescriptor descriptor = new PluginDescriptor(
                "jira", "Jira Integration", "1.0.0",
                Set.of("issue_tracker"), "Jira Cloud integration");

        assertEquals("jira", descriptor.id());
        assertEquals("Jira Integration", descriptor.name());
        assertEquals("1.0.0", descriptor.version());
        assertTrue(descriptor.namespaces().contains("issue_tracker"));
    }

    @Test
    void should_throw_for_null_id() {
        assertThrows(IllegalArgumentException.class, () ->
                new PluginDescriptor(null, "name", "1.0", Set.of("ns"), "desc"));
    }

    @Test
    void should_throw_for_blank_id() {
        assertThrows(IllegalArgumentException.class, () ->
                new PluginDescriptor("  ", "name", "1.0", Set.of("ns"), "desc"));
    }

    @Test
    void should_throw_for_null_namespaces() {
        assertThrows(IllegalArgumentException.class, () ->
                new PluginDescriptor("id", "name", "1.0", null, "desc"));
    }

    @Test
    void should_throw_for_empty_namespaces() {
        assertThrows(IllegalArgumentException.class, () ->
                new PluginDescriptor("id", "name", "1.0", Set.of(), "desc"));
    }

    @Test
    void should_support_multiple_namespaces() {
        PluginDescriptor descriptor = new PluginDescriptor(
                "source_control", "Source Control", "1.0.0",
                Set.of("source_control", "code_context"), "Multi-namespace plugin");

        assertEquals(2, descriptor.namespaces().size());
        assertTrue(descriptor.namespaces().contains("source_control"));
        assertTrue(descriptor.namespaces().contains("code_context"));
    }
}