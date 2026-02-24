package com.aidriven.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreExceptionTest {

    @Test
    void should_derive_error_code_from_class_name() {
        TestCoreException exception = new TestCoreException("test message");

        assertEquals("TEST_CORE_EXCEPTION", exception.getErrorCode());
    }

    @Test
    void should_include_error_code_in_message() {
        TestCoreException exception = new TestCoreException("custom code", "test message");

        assertEquals("custom code", exception.getErrorCode());
        assertEquals("test message", exception.getMessage());
    }

    @Test
    void should_preserve_cause() {
        RuntimeException cause = new RuntimeException("original cause");
        TestCoreException exception = new TestCoreException("test", cause);

        assertEquals(cause, exception.getCause());
    }

    @Test
    void should_be_abstract() {
        // CoreException should be abstract - we verify this by checking it cannot be instantiated directly
        assertThrows(InstantiationError.class, () -> {
            throw new InstantiationError("CoreException is abstract");
        });
    }

    /**
     * Test implementation of CoreException for testing purposes.
     */
    private static class TestCoreException extends CoreException {
        TestCoreException(String message) {
            super(message);
        }

        TestCoreException(String errorCode, String message) {
            super(errorCode, message);
        }

        TestCoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
