package com.aidriven.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictExceptionTest {

    @Test
    void should_create_exception_with_message_and_body() {
        String message = "Branch already exists";
        String responseBody = "{\"error\": \"conflict\"}";

        ConflictException exception = new ConflictException(message, responseBody);

        assertEquals(message, exception.getMessage());
        assertEquals(responseBody, exception.getResponseBody());
        assertEquals(409, exception.getStatusCode());
    }

    @Test
    void should_create_exception_with_cause() {
        String message = "Resource conflict";
        String responseBody = "{\"type\": \"error\"}";
        RuntimeException cause = new RuntimeException("original error");

        ConflictException exception = new ConflictException(message, responseBody, cause);

        assertEquals(message, exception.getMessage());
        assertEquals(responseBody, exception.getResponseBody());
        assertEquals(409, exception.getStatusCode());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void should_format_toString_correctly() {
        ConflictException exception = new ConflictException("Branch exists", "{}");

        String result = exception.toString();

        assertTrue(result.contains("ConflictException"));
        assertTrue(result.contains("409"));
        assertTrue(result.contains("Branch exists"));
    }

    @Test
    void should_extend_HttpClientException() {
        ConflictException exception = new ConflictException("test", "body");

        assertNotNull(exception);
        assertTrue(exception instanceof HttpClientException);
    }
}
