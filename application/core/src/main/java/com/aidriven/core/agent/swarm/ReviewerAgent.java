package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.exception.AgentExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Specialized worker agent for peer reviewing code changes.
 * Focuses on security, bugs, and style.
 */
@Slf4j
@RequiredArgsConstructor
public class ReviewerAgent implements WorkerAgent {

    private final AgentOrchestrator orchestrator;

    @Override
    public AgentResponse process(AgentRequest request) throws AgentExecutionException {
        log.info("ReviewerAgent processing review for ticket={}", request.ticketKey());
        // Reviewer agent uses the specialized REVIEW intent
        return orchestrator.process(request, CommentIntent.REVIEW);
    }
}
