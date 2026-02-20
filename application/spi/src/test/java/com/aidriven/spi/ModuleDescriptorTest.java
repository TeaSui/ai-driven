package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleDescriptorTest {

    @Test
    void should_create_descriptor_with_builder() {
        ModuleDescriptor descriptor = ModuleDescriptor.builder("jira-client", ModuleCategory.ISSUE_TRACKER)
                .name("Jira Client")
                .version("1.0.0")
                .description("Jira Cloud REST API client")
                .requiredConfigs("jira.baseUrl", "jira.secretArn")
                .dependencies("core")
                .capabilities("issue-tracking", "comment-management")
                .build();

        assertEquals("jira-client", descriptor.id());
        assertEquals("Jira Client", descriptor.name());
        assertEquals(ModuleCategory.ISSUE_TRACKER, descriptor.category());
        assertEquals(List.of("jira.baseUrl", "jira.secretArn"), descriptor.requiredConfigs());
        assertEquals(List.of("core"), descriptor.dependencies());
        assertEquals(List.of("issue-tracking", "comment-management"), descriptor.capabilities());
    }

    @Test
    void should_throw_for_null_id() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModuleDescriptor(null, "name", "1.0", ModuleCategory.AI_ENGINE,
                        "desc", List.of(), List.of(), List.of()));
    }

    @Test
    void should_throw_for_blank_id() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModuleDescriptor("  ", "name", "1.0", ModuleCategory.AI_ENGINE,
                        "desc", List.of(), List.of(), List.of()));
    }

    @Test
    void should_throw_for_null_category() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModuleDescriptor("test", "name", "1.0", null,
                        "desc", List.of(), List.of(), List.of()));
    }

    @Test
    void should_default_null_lists_to_empty() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "test", "Test", "1.0", ModuleCategory.DATA,
                "desc", null, null, null);

        assertNotNull(descriptor.requiredConfigs());
        assertNotNull(descriptor.dependencies());
        assertNotNull(descriptor.capabilities());
        assertTrue(descriptor.requiredConfigs().isEmpty());
    }
}
