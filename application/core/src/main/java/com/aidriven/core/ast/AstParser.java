package com.aidriven.core.ast;

import java.util.List;

/**
 * Contract for parsing source code into a structured outline tree.
 */
public interface AstParser {
    /**
     * Parses the given file content into a list of root code nodes.
     *
     * @param filename The name of the file (used to determine language/fallback)
     * @param content  The raw file content
     * @param maxDepth The maximum depth of elements to extract (0 = root/class
     *                 only, 1 = include methods, etc.)
     * @return A list of parsed root nodes, or a single RAW_TEXT node if fallback
     *         occurs
     */
    List<CodeNode> parse(String filename, String content, int maxDepth);
}
