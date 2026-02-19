package com.aidriven.contracts.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginCapabilityTest {

    @Test
    void should_have_all_expected_capabilities() {
        assertEquals(4, PluginCapability.values().length);
        assertNotNull(PluginCapability.SOURCE_CONTROL);
        assertNotNull(PluginCapability.ISSUE_TRACKER);
        assertNotNull(PluginCapability.AI_MODEL);
        assertNotNull(PluginCapability.TOOL_PROVIDER);
    }

    @Test
    void should_parse_from_string() {
        assertEquals(PluginCapability.SOURCE_CONTROL, PluginCapability.valueOf("SOURCE_CONTROL"));
        assertEquals(PluginCapability.TOOL_PROVIDER, PluginCapability.valueOf("TOOL_PROVIDER"));
    }
}
