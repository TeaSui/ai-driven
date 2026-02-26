package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.exception.AgentExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Specialized worker agent for research and Q&A tasks.
 * Optimized for codebase exploration without making modifications.
 */
@Slf4j
@RequiredArgsConstructor
public class ResearcherAgent implements WorkerAgent {

    private final AgentOrchestrator orchestrator;

    @Override
    public AgentResponse process(AgentRequest request) throws AgentExecutionException {
        log.info("ResearcherAgent processing Q&A request for ticket={}", request.ticketKey());
        // Researcher agent always treats the request as a command/question
        return orchestrator.process(request, CommentIntent.AI_COMMAND);
    }
}
