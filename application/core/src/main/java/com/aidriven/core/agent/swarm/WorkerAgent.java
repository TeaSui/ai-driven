package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.exception.AgentExecutionException;

/**
 * Interface for specialized worker agents in the swarm.
 */
public interface WorkerAgent {
    /**
     * Processes an agent request and returns a response.
     *
     * @param request The request to process
     * @return The agent response
     * @throws AgentExecutionException if an error occurs during processing
     */
    AgentResponse process(AgentRequest request) throws AgentExecutionException;
}
