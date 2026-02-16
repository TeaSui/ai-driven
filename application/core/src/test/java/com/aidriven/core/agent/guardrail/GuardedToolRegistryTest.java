package com.aidriven.core.agent.guardrail;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardedToolRegistryTest {

    @Mock
    private ToolRegistry delegate;

    @Mock
    private ApprovalStore approvalStore;

    private ToolRiskRegistry riskRegistry;
    private GuardedToolRegistry guardedRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        riskRegistry = new ToolRiskRegistry();
        guardedRegistry = new GuardedToolRegistry(delegate, riskRegistry, approvalStore, true);
    }

    @Test
    void execute_lowRiskTool_delegatesDirectly() {
        ToolCall call = toolCall("source_control_get_file");
        ToolResult expected = ToolResult.success(call.id(), "file content");
        when(delegate.execute(call)).thenReturn(expected);

        ToolResult result = guardedRegistry.execute(call, "TICKET-1", "user1");

        assertEquals(expected, result);
        verify(delegate).execute(call);
        verifyNoInteractions(approvalStore);
    }

    @Test
    void execute_highRiskTool_storesApprovalAndReturnsPrompt() {
        ToolCall call = toolCall("source_control_merge_pr");

        ToolResult result = guardedRegistry.execute(call, "TICKET-1", "user1");

        assertFalse(result.isError());
        assertTrue(result.content().contains("Approval required"));
        verify(approvalStore).storePendingApproval(
                eq("TICKET-1"), eq(call.id()), eq("source_control_merge_pr"),
                any(), eq(RiskLevel.HIGH), any(), eq("user1"));
        verify(delegate, never()).execute(any());
    }

    @Test
    void execute_guardrailsDisabled_alwaysDelegates() {
        GuardedToolRegistry disabled = new GuardedToolRegistry(delegate, riskRegistry, approvalStore, false);
        ToolCall call = toolCall("source_control_merge_pr");
        ToolResult expected = ToolResult.success(call.id(), "merged");
        when(delegate.execute(call)).thenReturn(expected);

        ToolResult result = disabled.execute(call, "TICKET-1", "user1");

        assertEquals(expected, result);
        verify(delegate).execute(call);
        verifyNoInteractions(approvalStore);
    }

    @Test
    void executeApproved_delegatesAndConsumes() throws Exception {
        ApprovalStore.PendingApproval approval = new ApprovalStore.PendingApproval(
                "AGENT#TICKET-1", "APPROVAL#2025-01-01T00:00:00Z",
                "call-id-1", "source_control_merge_pr",
                "{\"prId\":\"123\"}", RiskLevel.HIGH,
                "Merge PR #123", "user1",
                java.time.Instant.now());

        ToolResult expected = ToolResult.success("call-id-1", "PR merged");
        when(delegate.execute(any())).thenReturn(expected);

        ToolResult result = guardedRegistry.executeApproved("TICKET-1", approval);

        assertEquals(expected, result);
        verify(delegate).execute(any());
        verify(approvalStore).consumeApproval("TICKET-1", approval.sk());
    }

    private ToolCall toolCall(String name) {
        return new ToolCall("test-id", name, mapper.createObjectNode());
    }
}
