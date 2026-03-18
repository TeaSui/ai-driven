package com.aidriven.core.agent.swarm;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.exception.AgentExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

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
    private final WorkerAgent reviewerAgent;
    private final WorkerAgent testerAgent;

    private static final int MAX_REVIEW_LOOPS = 2;

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
            return handleImplementationWithFullLoop(request);
        }
    }

    private AgentResponse handleImplementationWithFullLoop(AgentRequest request) throws AgentExecutionException {
        AgentResponse coderResponse = coderAgent.process(request);

        // Start full sequence: Coder -> Reviewer -> Tester
        for (int i = 0; i < MAX_REVIEW_LOOPS; i++) {
            log.info("SwarmOrchestrator starting sequence turn {} for ticket={}", i + 1, request.ticketKey());

            // 1. Review
            AgentResponse reviewResponse = reviewerAgent.process(request);
            if (!reviewResponse.text().trim().toUpperCase().startsWith("APPROVED")) {
                log.info("Review REJECTED for ticket={}, looping back to Coder", request.ticketKey());
                coderResponse = coderAgent.process(request);
                continue;
            }

            log.info("Review APPROVED for ticket={}, proceeding to Test", request.ticketKey());

            // 2. Test
            AgentResponse testResponse = testerAgent.process(request);
            if (testResponse.text().trim().toUpperCase().startsWith("PASSED")) {
                log.info("Testing PASSED for ticket={}", request.ticketKey());
                return new AgentResponse(
                        coderResponse.text() + "\n\n### 🔍 Peer Review\n" + reviewResponse.text()
                                + "\n\n### 🧪 Automated Testing\n" + testResponse.text(),
                        mergeTools(coderResponse, reviewResponse, testResponse),
                        coderResponse.tokenCount() + reviewResponse.tokenCount() + testResponse.tokenCount(),
                        coderResponse.turnCount() + reviewResponse.turnCount() + testResponse.turnCount());
            }

            log.info("Testing FAILED for ticket={}, looping back to Coder", request.ticketKey());
            coderResponse = coderAgent.process(request);
        }

        return coderResponse;
    }

    private List<String> mergeTools(AgentResponse... responses) {
        List<String> combined = new ArrayList<>();
        for (AgentResponse r : responses) {
            combined.addAll(r.toolsUsed());
        }
        return combined.stream().distinct().toList();
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
