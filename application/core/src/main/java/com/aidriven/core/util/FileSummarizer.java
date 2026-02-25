package com.aidriven.core.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces large source files to structural summaries (signatures, declarations)
 * for inclusion in LLM context, targeting a 60–80% token reduction.
 *
 * <p>
 * Supports Java, TypeScript/JavaScript, and Python. All other file types
 * fall back to plain truncation with a notice. Files below the threshold are
 * returned unchanged.
 *
 * <p>
 * All methods are stateless and thread-safe.
 */
@Slf4j
public class FileSummarizer {

    private static final String TRUNCATION_NOTICE = "\n[... file truncated at summarization threshold ...]\n";

    private final int thresholdChars;

    public FileSummarizer(int thresholdChars) {
        this.thresholdChars = thresholdChars;
    }

    /**
     * Summarizes {@code content} if it exceeds the threshold.
     *
     * @param content   raw file content
     * @param extension file extension without the leading dot (e.g. "java", "ts")
     * @return summarized or original content, never null
     */
    public String summarize(String content, String extension) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        if (content.length() <= thresholdChars) {
            return content;
        }

        String ext = extension != null ? extension.toLowerCase() : "";
        String summary = switch (ext) {
            case "java" -> summarizeJava(content);
            case "ts", "tsx" -> summarizeTypeScript(content);
            case "js", "jsx", "mjs" -> summarizeTypeScript(content); // same grammar
            case "py" -> summarizePython(content);
            default -> truncateWithNotice(content);
        };

        int originalLen = content.length();
        int summaryLen = summary.length();
        log.debug("FileSummarizer: .{} reduced {}->{} chars ({:.0f}% reduction)",
                ext, originalLen, summaryLen,
                summaryLen < originalLen ? 100.0 * (originalLen - summaryLen) / originalLen : 0.0);

        return summary;
    }

    // ─── Java ────────────────────────────────────────────────────────────────

    /**
     * Extracts:
     * - package declaration
     * - import statements (collapsed to first N)
     * - class/interface/enum/record declarations
     * - field declarations
     * - method/constructor signatures (first line only — no body)
     * - annotations on class/method level
     */
    private String summarizeJava(String content) {
        List<String> lines = List.of(content.split("\n", -1));
        StringBuilder sb = new StringBuilder();
        sb.append("[Java structural summary — method bodies omitted]\n\n");

        boolean inBlockComment = false;
        int braceDepth = 0;
        boolean inMethodBody = false;
        int methodBodyStartDepth = -1;

        // Patterns
        Pattern packagePat = Pattern.compile("^package\\s+[\\w.]+;");
        Pattern importPat = Pattern.compile("^import\\s+.*?;");
        Pattern classPat = Pattern.compile(
                "^(\\s*)(public|protected|private|abstract|final|sealed|static)?\\s*"
                        + "(class|interface|enum|record|@interface)\\s+\\w+");
        Pattern fieldPat = Pattern.compile(
                "^(\\s+)(public|protected|private|static|final|volatile|transient|"
                        + "readonly)?\\s*(static\\s+)?(final\\s+)?[\\w<>\\[\\].,?\\s]+ \\w+\\s*(=|;)");
        Pattern methodPat = Pattern.compile(
                "^(\\s+)(public|protected|private|static|default|abstract|final|"
                        + "synchronized|native|override|override)?\\s*(<[^>]+>\\s*)?"
                        + "[\\w<>\\[\\].,?\\s]+ \\w+\\s*\\([^)]*\\)");
        Pattern annotationPat = Pattern.compile("^\\s*@[A-Za-z]");

        int importCount = 0;
        String lastAnnotation = null;

        for (String line : lines) {
            String trimmed = line.trim();

            // Track block comments
            if (!inBlockComment && trimmed.startsWith("/*")) {
                inBlockComment = true;
            }
            if (inBlockComment) {
                if (trimmed.contains("*/"))
                    inBlockComment = false;
                continue; // skip comment lines
            }
            if (trimmed.startsWith("//"))
                continue; // skip line comments

            // Track brace depth
            long opens = trimmed.chars().filter(c -> c == '{').count();
            long closes = trimmed.chars().filter(c -> c == '}').count();

            // Package
            if (packagePat.matcher(trimmed).find()) {
                sb.append(line).append("\n");
                continue;
            }

            // Imports (keep first 20, then summarise remainder)
            if (importPat.matcher(trimmed).find()) {
                importCount++;
                if (importCount <= 20) {
                    sb.append(line).append("\n");
                } else if (importCount == 21) {
                    sb.append("// ... (additional imports omitted)\n");
                }
                continue;
            }

            // Annotations
            if (annotationPat.matcher(line).find()) {
                lastAnnotation = line;
                continue;
            }

            // Class/interface/enum declaration
            if (classPat.matcher(trimmed).find()) {
                if (lastAnnotation != null) {
                    sb.append(lastAnnotation).append("\n");
                }
                // Emit just the declaration line (stop at { or newline)
                String decl = line.contains("{") ? line.substring(0, line.lastIndexOf('{') + 1) : line;
                sb.append(decl).append("\n");
                lastAnnotation = null;
                braceDepth += (int) (opens - closes);
                continue;
            }

            // Update depth after processing struct declarations
            if (inMethodBody) {
                braceDepth += (int) (opens - closes);
                if (braceDepth <= methodBodyStartDepth) {
                    inMethodBody = false;
                    methodBodyStartDepth = -1;
                    sb.append("    }\n"); // close marker
                }
                continue; // skip body lines
            }

            // Field declarations (depth == 1 → class body)
            if (braceDepth == 1 && fieldPat.matcher(line).find()) {
                sb.append(line).append("\n");
                braceDepth += (int) (opens - closes);
                lastAnnotation = null;
                continue;
            }

            // Abstract method / interface method signature (ends in ';', no body)
            if (braceDepth >= 1 && trimmed.contains("(") && trimmed.endsWith(";")) {
                if (lastAnnotation != null) {
                    sb.append(lastAnnotation).append("\n");
                }
                sb.append(line).append("\n");
                lastAnnotation = null;
                braceDepth += (int) (opens - closes);
                continue;
            }

            // Method/constructor signature at class body depth (has body)
            if (braceDepth >= 1 && trimmed.contains("(") && methodPat.matcher(line).find() && trimmed.endsWith("{")) {
                if (lastAnnotation != null) {
                    sb.append(lastAnnotation).append("\n");
                }
                String sig = line.substring(0, line.lastIndexOf('{') + 1);
                sb.append(sig).append(" /* ... */ }\n");
                lastAnnotation = null;
                // The brace was opened → enter body to skip it
                methodBodyStartDepth = braceDepth;
                braceDepth += (int) (opens - closes);
                inMethodBody = braceDepth > methodBodyStartDepth;
                if (!inMethodBody)
                    methodBodyStartDepth = -1;
                continue;
            }

            // Closing braces
            if (trimmed.equals("}") || trimmed.equals("};")) {
                braceDepth = Math.max(0, braceDepth + (int) (opens - closes));
                sb.append(line).append("\n");
                continue;
            }

            // Everything else at depth 0 (annotations, etc.)
            braceDepth += (int) (opens - closes);
            lastAnnotation = null;
        }

        return sb.toString();
    }

    // ─── TypeScript / JavaScript ─────────────────────────────────────────────

    /**
     * Extracts:
     * - interface/type declarations
     * - export/class declarations
     * - function/method signatures (no body)
     * - import statements (first 15)
     */
    private String summarizeTypeScript(String content) {
        List<String> lines = List.of(content.split("\n", -1));
        StringBuilder sb = new StringBuilder();
        sb.append("[TypeScript/JS structural summary — function bodies omitted]\n\n");

        Pattern importPat = Pattern.compile("^(import|export\\s+\\{)");
        Pattern interfacePat = Pattern.compile("^(export\\s+)?(interface|type)\\s+\\w+");
        Pattern classPat = Pattern.compile("^(export\\s+)?(abstract\\s+)?class\\s+\\w+");
        Pattern funcPat = Pattern.compile(
                "^(export\\s+)?(async\\s+)?function\\s+\\w+\\s*\\(");
        Pattern arrowExportPat = Pattern.compile(
                "^export\\s+(const|let)\\s+\\w+\\s*=\\s*(async\\s+)?\\(");
        Pattern methodPat = Pattern.compile(
                "^\\s+(public|private|protected|static|async|get|set)?\\s*\\w+\\s*\\(");

        int importCount = 0;
        int braceDepth = 0;
        boolean inFuncBody = false;
        int funcBodyDepth = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            if (trimmed.startsWith("//") || trimmed.startsWith("*"))
                continue;

            long opens = trimmed.chars().filter(c -> c == '{').count();
            long closes = trimmed.chars().filter(c -> c == '}').count();

            if (inFuncBody) {
                braceDepth += (int) (opens - closes);
                if (braceDepth <= funcBodyDepth) {
                    inFuncBody = false;
                    sb.append("}\n");
                }
                continue;
            }

            if (importPat.matcher(trimmed).find()) {
                importCount++;
                if (importCount <= 15)
                    sb.append(line).append("\n");
                else if (importCount == 16)
                    sb.append("// ... (imports omitted)\n");
                braceDepth += (int) (opens - closes);
                continue;
            }

            if (interfacePat.matcher(trimmed).find()) {
                // Emit entire interface block (interfaces are usually compact)
                sb.append(line).append("\n");
                braceDepth += (int) (opens - closes);
                continue;
            }

            if (classPat.matcher(trimmed).find() || funcPat.matcher(trimmed).find()
                    || arrowExportPat.matcher(trimmed).find()) {
                String sig = trimmed.contains("{")
                        ? line.substring(0, line.lastIndexOf('{') + 1)
                        : line;
                sb.append(sig);
                if (trimmed.contains("{")) {
                    sb.append(" /* ... */ }\n");
                    funcBodyDepth = braceDepth;
                    braceDepth += (int) (opens - closes);
                    inFuncBody = braceDepth > funcBodyDepth;
                } else {
                    sb.append("\n");
                    braceDepth += (int) (opens - closes);
                }
                continue;
            }

            if (methodPat.matcher(line).find() && braceDepth >= 1) {
                String sig = line.contains("{")
                        ? line.substring(0, line.lastIndexOf('{') + 1) + " /* ... */ }"
                        : line;
                sb.append(sig).append("\n");
                if (line.contains("{")) {
                    funcBodyDepth = braceDepth;
                    braceDepth += (int) (opens - closes);
                    inFuncBody = braceDepth > funcBodyDepth;
                }
                continue;
            }

            // Keep closing braces (class/type closings)
            if (trimmed.startsWith("}")) {
                sb.append(line).append("\n");
            }
            braceDepth += (int) (opens - closes);
        }

        return sb.toString();
    }

    // ─── Python ─────────────────────────────────────────────────────────────

    /**
     * Extracts:
     * - import statements (first 15)
     * - class declarations
     * - function/method signatures + one-line docstring
     */
    private String summarizePython(String content) {
        List<String> lines = List.of(content.split("\n", -1));
        StringBuilder sb = new StringBuilder();
        sb.append("[Python structural summary — function bodies omitted]\n\n");

        Pattern importPat = Pattern.compile("^(import|from)\\s+");
        Pattern classPat = Pattern.compile("^class\\s+\\w+");
        Pattern funcPat = Pattern.compile("^(    )?def\\s+\\w+\\s*\\(");
        Pattern docPat = Pattern.compile("\"\"\".*\"\"\"|'''.*'''|\"\"\".*|'''.*");

        int importCount = 0;
        boolean inFuncBody = false;
        int funcIndent = 0;
        boolean nextIsDocstring = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (importPat.matcher(trimmed).find()) {
                importCount++;
                if (importCount <= 15)
                    sb.append(line).append("\n");
                else if (importCount == 16)
                    sb.append("# ... (imports omitted)\n");
                continue;
            }

            if (classPat.matcher(trimmed).find()) {
                inFuncBody = false;
                sb.append(line).append("\n");
                continue;
            }

            if (funcPat.matcher(line).find()) {
                inFuncBody = true;
                funcIndent = leadingSpaces(line);
                nextIsDocstring = true;
                sb.append(line).append("\n");
                continue;
            }

            if (inFuncBody) {
                if (nextIsDocstring && (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''"))) {
                    sb.append(line).append("\n");
                    if (!trimmed.substring(3).contains("\"\"\"") && !trimmed.substring(3).contains("'''")) {
                        // multi-line docstring — find closing
                    }
                    nextIsDocstring = false;
                    continue;
                }
                nextIsDocstring = false;
                // Exit function body when indentation decreases
                if (!trimmed.isEmpty() && leadingSpaces(line) <= funcIndent) {
                    inFuncBody = false;
                    // Fall through to re-process this line
                } else {
                    continue; // skip body
                }
            }

            // De-indented line — check if it's a new function or class
            if (classPat.matcher(trimmed).find()) {
                sb.append(line).append("\n");
            } else if (funcPat.matcher(line).find()) {
                inFuncBody = true;
                funcIndent = leadingSpaces(line);
                nextIsDocstring = true;
                sb.append(line).append("\n");
            }
            // else: skip module-level code
        }

        return sb.toString();
    }

    private int leadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ')
                count++;
            else if (c == '\t')
                count += 4;
            else
                break;
        }
        return count;
    }

    // ─── Fallback ─────────────────────────────────────────────────────────────

    private String truncateWithNotice(String content) {
        return content.substring(0, thresholdChars) + TRUNCATION_NOTICE;
    }
}
