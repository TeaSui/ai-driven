package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.core.exception.AgentExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmOrchestratorTest {

    @Mock
    private AiClient routingClient;
    @Mock
    private WorkerAgent coderAgent;
    @Mock
    private WorkerAgent researcherAgent;
    @Mock
    private WorkerAgent reviewerAgent;
    @Mock
    private WorkerAgent testerAgent;

    private SwarmOrchestrator orchestrator;
    private AgentRequest request;

    @BeforeEach
    void setUp() {
        orchestrator = new SwarmOrchestrator(routingClient, coderAgent, researcherAgent, reviewerAgent, testerAgent);
        request = new AgentRequest(
                "TEST-1",
                "github",
                "Implement a new feature",
                "user1",
                null,
                "ack-1",
                OperationContext.builder().tenantId("tenant-1").build(),
                Collections.emptyMap());
    }

    @Test
    void shouldRouteToResearcherWhenIntentIsQuestion() throws AgentExecutionException {
        // Arrange
        AgentResponse expectedResponse = new AgentResponse("Research result", List.of(), 100, 1);
        when(researcherAgent.process(request)).thenReturn(expectedResponse);

        // Act
        AgentResponse response = orchestrator.process(request, CommentIntent.QUESTION);

        // Assert
        assertThat(response).isEqualTo(expectedResponse);
        verify(researcherAgent).process(request);
        verifyNoInteractions(coderAgent, reviewerAgent, testerAgent);
    }

    @Test
    void shouldExecuteFullSequenceWhenImplementationApprovedAndTestsPass() throws Exception {
        // Arrange
        when(routingClient.chat(any(), any())).thenReturn("IMPLEMENTATION");

        AgentResponse coderResp = new AgentResponse("Code implemented", List.of("write_file"), 500, 2);
        AgentResponse reviewResp = new AgentResponse("APPROVED: LGTM", List.of("view_file"), 200, 1);
        AgentResponse testResp = new AgentResponse("PASSED: All tests passed", List.of("run_tests"), 300, 1);

        when(coderAgent.process(request)).thenReturn(coderResp);
        when(reviewerAgent.process(request)).thenReturn(reviewResp);
        when(testerAgent.process(request)).thenReturn(testResp);

        // Act
        AgentResponse response = orchestrator.process(request, CommentIntent.AI_COMMAND);

        // Assert
        assertThat(response.text()).contains("Code implemented");
        assertThat(response.text()).contains("🔍 Peer Review");
        assertThat(response.text()).contains("🧪 Automated Testing");
        assertThat(response.tokenCount()).isEqualTo(1000);
        assertThat(response.turnCount()).isEqualTo(4);

        verify(coderAgent).process(request);
        verify(reviewerAgent).process(request);
        verify(testerAgent).process(request);
    }

    @Test
    void shouldRetryCoderWhenReviewerRejects() throws Exception {
        // Arrange
        when(routingClient.chat(any(), any())).thenReturn("IMPLEMENTATION");

        AgentResponse coderResp1 = new AgentResponse("Code v1", List.of(), 100, 1);
        AgentResponse reviewResp1 = new AgentResponse("REJECTED: Fix this", List.of(), 100, 1);
        AgentResponse coderResp2 = new AgentResponse("Code v2", List.of(), 100, 1);
        AgentResponse reviewResp2 = new AgentResponse("APPROVED", List.of(), 100, 1);
        AgentResponse testResp = new AgentResponse("PASSED", List.of(), 100, 1);

        when(coderAgent.process(request)).thenReturn(coderResp1, coderResp2);
        when(reviewerAgent.process(request)).thenReturn(reviewResp1, reviewResp2);
        when(testerAgent.process(request)).thenReturn(testResp);

        // Act
        AgentResponse response = orchestrator.process(request, CommentIntent.AI_COMMAND);

        // Assert
        verify(coderAgent, times(2)).process(request);
        verify(reviewerAgent, times(2)).process(request);
        verify(testerAgent).process(request);
        assertThat(response.text()).contains("Code v2");
    }

    @Test
    void shouldRetryCoderWhenTesterFails() throws Exception {
        // Arrange
        when(routingClient.chat(any(), any())).thenReturn("IMPLEMENTATION");

        AgentResponse coderResp1 = new AgentResponse("Code v1", List.of(), 100, 1);
        AgentResponse reviewResp = new AgentResponse("APPROVED", List.of(), 100, 1);
        AgentResponse testResp1 = new AgentResponse("FAILED: Test error", List.of(), 100, 1);
        AgentResponse coderResp2 = new AgentResponse("Code v2", List.of(), 100, 1);
        AgentResponse testResp2 = new AgentResponse("PASSED", List.of(), 100, 1);

        when(coderAgent.process(request)).thenReturn(coderResp1, coderResp2);
        when(reviewerAgent.process(request)).thenReturn(reviewResp);
        when(testerAgent.process(request)).thenReturn(testResp1, testResp2);

        // Act
        AgentResponse response = orchestrator.process(request, CommentIntent.AI_COMMAND);

        // Assert
        verify(coderAgent, times(2)).process(request);
        verify(testerAgent, times(2)).process(request);
        assertThat(response.text()).contains("Code v2");
    }
}
