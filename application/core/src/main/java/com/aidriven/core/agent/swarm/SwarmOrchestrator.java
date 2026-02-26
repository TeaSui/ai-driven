package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.exception.AgentExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * The main orchestrator for the Cross-Agent Swarm.
 * Routes requests to specialized agents based on intent.
 */
@Slf4j
@RequiredArgsConstructor
public class SwarmOrchestrator {

    private final AiClient routingClient;
    private final WorkerAgent coderAgent;
    private final WorkerAgent researcherAgent;

    /**
     * Processes an agent request by routing it to the appropriate specialized
     * worker.
     */
    public AgentResponse process(AgentRequest request, CommentIntent intent) throws AgentExecutionException {
        log.info("SwarmOrchestrator processing ticket={} with intent={}", request.ticketKey(), intent);

        // Map base intent if already known
        if (intent == CommentIntent.QUESTION) {
            return researcherAgent.process(request);
        }

        // For commands, use LLM to classify if it's research or implementation
        String swarmIntent = classifyIntent(request);
        log.info("Classified swarm intent: {}", swarmIntent);

        if ("RESEARCH".equals(swarmIntent)) {
            return researcherAgent.process(request);
        } else {
            return coderAgent.process(request);
        }
    }

    /**
     * Uses a fast LLM call to classify the request intent.
     */
    private String classifyIntent(AgentRequest request) {
        String prompt = String.format(
                """
                        You are a task router for a multi-agent system.
                        Your goal is to classify if the following user request is a RESEARCH/Q&A task or an IMPLEMENTATION/CODING task.

                        - RESEARCH: Asking questions about how things work, finding where code is located, explaining logic, or general exploration.
                        - IMPLEMENTATION: Requests to write code, fix bugs, create PRs, or make any changes to files.

                        Request context:
                        Ticket: %s
                        Author: %s
                        Body: %s

                        Respond with ONLY 'RESEARCH' or 'IMPLEMENTATION'.
                        """,
                request.ticketKey(), request.commentAuthor(), request.commentBody());

        try {
            // Use a simple non-tool chat call for classification
            String result = routingClient.chat(prompt, "").trim().toUpperCase();
            if (result.contains("RESEARCH"))
                return "RESEARCH";
            if (result.contains("IMPLEMENTATION"))
                return "IMPLEMENTATION";
            return "IMPLEMENTATION"; // Default to implementation for safety
        } catch (Exception e) {
            log.error("Failed to classify intent, defaulting to IMPLEMENTATION", e);
            return "IMPLEMENTATION";
        }
    }
}
