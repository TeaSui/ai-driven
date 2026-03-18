package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.exception.AgentExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Specialized worker agent for generating and running tests.
 * Focuses on edge cases and contract verification.
 */
@Slf4j
@RequiredArgsConstructor
public class TesterAgent implements WorkerAgent {

    private final AgentOrchestrator orchestrator;

    @Override
    public AgentResponse process(AgentRequest request) throws AgentExecutionException {
        log.info("TesterAgent processing test request for ticket={}", request.ticketKey());
        // Tester agent uses the specialized TEST intent
        return orchestrator.process(request, CommentIntent.TEST);
    }
}
