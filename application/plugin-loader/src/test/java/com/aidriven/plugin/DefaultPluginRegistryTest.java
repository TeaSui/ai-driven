package com.aidriven.plugin;

import com.aidriven.contracts.plugin.PluginRegistry;
import com.aidriven.contracts.tool.ToolProviderContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultPluginRegistryTest {

    private DefaultPluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultPluginRegistry();
    }

    @Test
    void should_register_and_lookup_source_control() {
        registry.registerSourceControl("gitlab", (creds, config) -> null);

        assertTrue(registry.getSourceControlFactory("gitlab").isPresent());
        assertTrue(registry.getSourceControlFactory("GITLAB").isPresent());
        assertFalse(registry.getSourceControlFactory("azure-devops").isPresent());
    }

    @Test
    void should_register_and_lookup_issue_tracker() {
        registry.registerIssueTracker("linear", (creds, config) -> null);

        assertTrue(registry.getIssueTrackerFactory("linear").isPresent());
        assertFalse(registry.getIssueTrackerFactory("notion").isPresent());
    }

    @Test
    void should_register_and_lookup_ai_model() {
        registry.registerAiModel("openai", (creds, config) -> null);

        assertTrue(registry.getAiModelFactory("openai").isPresent());
        assertFalse(registry.getAiModelFactory("bedrock").isPresent());
    }

    @Test
    void should_register_and_lookup_tool_provider() {
        ToolProviderContract provider = new ToolProviderContract() {
            public String namespace() { return "monitoring"; }
            public List<ToolDefinition> toolDefinitions() { return List.of(); }
            public ToolExecutionResult execute(String id, String name, Map<String, Object> input) {
                return ToolExecutionResult.success(id, "");
            }
        };

        registry.registerToolProvider(provider);

        assertTrue(registry.getToolProvider("monitoring").isPresent());
        assertEquals(1, registry.getAllToolProviders().size());
    }

    @Test
    void should_throw_on_null_platform_id() {
        assertThrows(NullPointerException.class,
                () -> registry.registerSourceControl(null, (c, cfg) -> null));
    }

    @Test
    void should_throw_on_null_factory() {
        assertThrows(NullPointerException.class,
                () -> registry.registerSourceControl("gitlab", null));
    }

    @Test
    void should_throw_on_null_tool_provider() {
        assertThrows(NullPointerException.class,
                () -> registry.registerToolProvider(null));
    }

    @Test
    void should_return_registered_platform_sets() {
        registry.registerSourceControl("gitlab", (c, cfg) -> null);
        registry.registerSourceControl("azure-devops", (c, cfg) -> null);
        registry.registerIssueTracker("linear", (c, cfg) -> null);
        registry.registerAiModel("openai", (c, cfg) -> null);

        assertEquals(2, registry.getRegisteredSourceControlPlatforms().size());
        assertTrue(registry.getRegisteredSourceControlPlatforms().contains("gitlab"));
        assertEquals(1, registry.getRegisteredIssueTrackerPlatforms().size());
        assertEquals(1, registry.getRegisteredAiModelProviders().size());
    }

    @Test
    void should_overwrite_existing_registration() {
        PluginRegistry.SourceControlFactory factory1 = (c, cfg) -> null;
        PluginRegistry.SourceControlFactory factory2 = (c, cfg) -> null;

        registry.registerSourceControl("gitlab", factory1);
        registry.registerSourceControl("gitlab", factory2);

        // Should have the latest factory
        assertTrue(registry.getSourceControlFactory("gitlab").isPresent());
        assertEquals(1, registry.getRegisteredSourceControlPlatforms().size());
    }
}
