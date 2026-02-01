package com.aidriven.core.model;

/**
 * Processing states for a ticket.
 */
public enum ProcessingStatus {
    RECEIVED("RECEIVED"),
    IN_PROGRESS("IN_PROGRESS"),
    ANALYZING("ANALYZING"),
    GENERATING("GENERATING"),
    IN_REVIEW("IN_REVIEW"),
    DONE("DONE"),
    FAILED("FAILED"),
    SKIPPED("SKIPPED"),
    TEST_COMPLETED("TEST_COMPLETED");

    private final String value;
    
    ProcessingStatus(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static ProcessingStatus fromValue(String value) {
        for (ProcessingStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status: " + value);
    }
}
