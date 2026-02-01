package com.aidriven.core.model;

/**
 * Types of AI agents that can process tickets.
 */
public enum AgentType {
    BACKEND("backend"),
    FRONTEND("frontend"),
    SECURITY("security");
    
    private final String value;
    
    AgentType(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static AgentType fromValue(String value) {
        for (AgentType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown agent type: " + value);
    }
}
