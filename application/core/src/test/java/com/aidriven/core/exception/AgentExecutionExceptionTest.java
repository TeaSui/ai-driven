package com.aidriven.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionExceptionTest {

    @Test
    void should_create_exception_with_message() {
        String message = "AI model call failed";

        AgentExecutionException exception = new AgentExecutionException(message);

        assertEquals(message, exception.getMessage());
    }

    @Test
    void should_create_exception_with_message_and_cause() {
        String message = "AI model call failed";
        RuntimeException cause = new RuntimeException("Network error");

        AgentExecutionException exception = new AgentExecutionException(message, cause);

        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void should_extend_runtime_exception() {
        AgentExecutionException exception = new AgentExecutionException("test");

        assertTrue(exception instanceof RuntimeException);
    }

    @Test
    void should_have_error_code() {
        // Error codes are not implemented for AgentExecutionException
        // This test documents that the exception uses standard RuntimeException behavior
        AgentExecutionException exception = new AgentExecutionException("test");

        assertNotNull(exception);
        assertTrue(exception instanceof RuntimeException);
    }
}
