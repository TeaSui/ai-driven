package com.aidriven.core.ast;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a parsed code element (e.g., class, method, field).
 */
@Data
@Builder
public class CodeNode {
    private String name;
    private String type; // e.g., "CLASS", "METHOD", "FIELD", "RAW_TEXT"
    private String signature; // Full signature including arguments for methods
    private int startLine;
    private int endLine;
    private List<CodeNode> children; // Nested elements (e.g., methods inside a class)
}
