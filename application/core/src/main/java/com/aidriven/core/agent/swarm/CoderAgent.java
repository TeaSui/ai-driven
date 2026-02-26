package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.exception.AgentExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Specialized worker agent for code implementation tasks.
 * Wraps the existing AgentOrchestrator.
 */
@Slf4j
@RequiredArgsConstructor
public class CoderAgent implements WorkerAgent {

    private final AgentOrchestrator orchestrator;

    @Override
    public AgentResponse process(AgentRequest request) throws AgentExecutionException {
        log.info("CoderAgent processing request for ticket={}", request.ticketKey());
        return orchestrator.process(request, CommentIntent.AI_COMMAND);
    }
}
