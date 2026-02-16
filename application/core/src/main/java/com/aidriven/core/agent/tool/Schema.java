package com.aidriven.core.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class for defining tool input schemas (JSON Schema).
 */
public class Schema {

    public static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> array(String description) {
        return Map.of("type", "array", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static class SchemaBuilder {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final java.util.List<String> required = new java.util.ArrayList<>();

        public SchemaBuilder required(String name, Map<String, Object> schema) {
            properties.put(name, schema);
            required.add(name);
            return this;
        }

        public SchemaBuilder optional(String name, Map<String, Object> schema) {
            properties.put(name, schema);
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        }

        // Provide implicit conversion to Map
        public Map<String, Object> toMap() {
            return build();
        }
    }
}
