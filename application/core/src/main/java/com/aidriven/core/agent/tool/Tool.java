package com.aidriven.core.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a tool definition for Claude's tool-use API.
 * Maps directly to a single entry in the "tools" array of the Messages API
 * request.
 */
public record Tool(String name, String description, Map<String, Object> inputSchema) {

    /**
     * Creates a Tool with a structured input schema.
     *
     * @param name        Tool name (e.g., "source_control_create_branch")
     * @param description Human-readable description of what the tool does
     * @param properties  Map of property name → property schema
     * @param required    Names of required properties
     */
    public static Tool of(String name, String description, Schema.SchemaBuilder schema) {
        return new Tool(name, description, schema.build());
    }

    /**
     * Creates a Tool with a structured input schema.
     *
     * @param name        Tool name (e.g., "source_control_create_branch")
     * @param description Human-readable description of what the tool does
     * @param properties  Map of property name → property schema
     * @param required    Names of required properties
     */
    public static Tool of(String name, String description, Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", required);
        }
        return new Tool(name, description, schema);
    }

    /** Convenience: create a string property schema. */
    public static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** Convenience: create an array property schema with string items. */
    public static Map<String, Object> stringArrayProp(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    /** Convenience: create an array property schema with object items. */
    public static Map<String, Object> objectArrayProp(String description, Map<String, Object> itemProperties) {
        return Map.of("type", "array", "description", description, "items",
                Map.of("type", "object", "properties", itemProperties));
    }

    /** Converts this tool to the Claude API format. */
    public Map<String, Object> toApiFormat() {
        return Map.of(
                "name", name,
                "description", description,
                "input_schema", inputSchema);
    }
}
