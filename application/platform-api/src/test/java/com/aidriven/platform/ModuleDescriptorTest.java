package com.aidriven.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModuleDescriptorTest {

    @Test
    void should_create_module_descriptor() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "bitbucket",
                "Bitbucket Cloud",
                ModuleDescriptor.ModuleType.SOURCE_CONTROL,
                "Bitbucket Cloud integration via REST API",
                List.of("secret_arn", "workspace", "repo_slug"),
                Map.of("api_version", "2.0"));

        assertEquals("bitbucket", descriptor.moduleId());
        assertEquals("Bitbucket Cloud", descriptor.displayName());
        assertEquals(ModuleDescriptor.ModuleType.SOURCE_CONTROL, descriptor.type());
        assertEquals(3, descriptor.requiredConfigKeys().size());
        assertEquals("2.0", descriptor.defaultConfig().get("api_version"));
    }

    @Test
    void should_have_all_module_types() {
        ModuleDescriptor.ModuleType[] types = ModuleDescriptor.ModuleType.values();

        assertEquals(7, types.length);
        assertNotNull(ModuleDescriptor.ModuleType.SOURCE_CONTROL);
        assertNotNull(ModuleDescriptor.ModuleType.ISSUE_TRACKER);
        assertNotNull(ModuleDescriptor.ModuleType.AI_PROVIDER);
        assertNotNull(ModuleDescriptor.ModuleType.CONTEXT_STRATEGY);
        assertNotNull(ModuleDescriptor.ModuleType.TOOL_PROVIDER);
        assertNotNull(ModuleDescriptor.ModuleType.NOTIFICATION);
        assertNotNull(ModuleDescriptor.ModuleType.OBSERVABILITY);
    }
}
