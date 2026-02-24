package com.aidriven.core.exception;

/**
 * Thrown when the agent's ReAct loop fails to complete normally.
 * Replaces raw {@link Exception} in
 * {@link com.aidriven.core.agent.AgentOrchestrator#process}
 * to give callers a typed exception to catch and handle gracefully.
 */
public class AgentExecutionException extends RuntimeException {

    public AgentExecutionException(String message) {
        super(message);
    }

    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
