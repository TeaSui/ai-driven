package com.aidriven.core.agent.guardrail;

import com.aidriven.core.agent.tool.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRiskRegistryTest {

    private ToolRiskRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new ToolRiskRegistry();
    }

    @Test
    void assessLowRisk_readOnlyTools() {
        assertEquals(RiskLevel.LOW, registry.assess(toolCall("source_control_get_file")));
        assertEquals(RiskLevel.LOW, registry.assess(toolCall("issue_tracker_search_issues")));
        assertEquals(RiskLevel.LOW, registry.assess(toolCall("code_context_list_files")));
        assertEquals(RiskLevel.LOW, registry.assess(toolCall("issue_tracker_add_comment")));
    }

    @Test
    void assessMediumRisk_writeTools() {
        assertEquals(RiskLevel.MEDIUM, registry.assess(toolCall("source_control_create_branch")));
        assertEquals(RiskLevel.MEDIUM, registry.assess(toolCall("source_control_commit_files")));
        assertEquals(RiskLevel.MEDIUM, registry.assess(toolCall("source_control_create_pr")));
    }

    @Test
    void assessHighRisk_destructiveTools() {
        assertEquals(RiskLevel.HIGH, registry.assess(toolCall("source_control_merge_pr")));
        assertEquals(RiskLevel.HIGH, registry.assess(toolCall("source_control_delete_branch")));
    }

    @Test
    void assessHighRisk_updateStatusToDone() {
        ObjectNode input = mapper.createObjectNode();
        input.put("status", "Done");
        ToolCall call = new ToolCall("id1", "issue_tracker_update_status", input);
        assertEquals(RiskLevel.HIGH, registry.assess(call));
    }

    @Test
    void assessMediumRisk_updateStatusToInProgress() {
        ObjectNode input = mapper.createObjectNode();
        input.put("status", "In Progress");
        ToolCall call = new ToolCall("id1", "issue_tracker_update_status", input);
        assertEquals(RiskLevel.MEDIUM, registry.assess(call));
    }

    @Test
    void buildPolicy_lowRisk_autoExecute() {
        ActionPolicy policy = registry.buildPolicy(toolCall("source_control_get_file"));
        assertFalse(policy.requiresApproval());
        assertEquals(RiskLevel.LOW, policy.level());
    }

    @Test
    void buildPolicy_highRisk_requiresApproval() {
        ActionPolicy policy = registry.buildPolicy(toolCall("source_control_merge_pr"));
        assertTrue(policy.requiresApproval());
        assertEquals(RiskLevel.HIGH, policy.level());
        assertNotNull(policy.approvalPrompt());
        assertTrue(policy.approvalPrompt().toLowerCase().contains("merge"));
    }

    @Test
    void exactOverride_takesHighestPriority() {
        // ToolRiskRegistry accepts exactOverrides via constructor
        var registryWithOverride = new ToolRiskRegistry(
                java.util.List.of(),
                java.util.Map.of("source_control_get_file", RiskLevel.HIGH));
        assertEquals(RiskLevel.HIGH, registryWithOverride.assess(toolCall("source_control_get_file")));
    }

    @Test
    void unknownTool_defaultsToMedium() {
        assertEquals(RiskLevel.MEDIUM, registry.assess(toolCall("unknown_tool_xyz")));
    }

    private ToolCall toolCall(String name) {
        return new ToolCall("test-id", name, mapper.createObjectNode());
    }
}
