package com.aidriven.core.ast.java;

import com.aidriven.core.ast.AstParser;
import com.aidriven.core.ast.CodeNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;

import java.util.ArrayList;
import java.util.List;

public class JavaAstParser implements AstParser {

    @Override
    public List<CodeNode> parse(String filename, String content, int maxDepth) {
        if (filename == null || !filename.endsWith(".java")) {
            return fallback(content);
        }

        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            List<CodeNode> rootNodes = new ArrayList<>();

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                // Determine if this is a top-level class (no enclosing class)
                if (clazz.isNestedType())
                    return;

                CodeNode classNode = CodeNode.builder()
                        .name(clazz.getNameAsString())
                        .type(clazz.isInterface() ? "INTERFACE" : "CLASS")
                        .signature((clazz.isInterface() ? "interface " : "class ") + clazz.getNameAsString())
                        .startLine(clazz.getBegin().map(p -> p.line).orElse(0))
                        .endLine(clazz.getEnd().map(p -> p.line).orElse(0))
                        .children(new ArrayList<>())
                        .build();

                if (maxDepth >= 1) {
                    // Extract methods
                    clazz.getMethods().forEach(method -> {
                        CodeNode methodNode = CodeNode.builder()
                                .name(method.getNameAsString())
                                .type("METHOD")
                                .signature(method.getDeclarationAsString(true, true, true))
                                .startLine(method.getBegin().map(p -> p.line).orElse(0))
                                .endLine(method.getEnd().map(p -> p.line).orElse(0))
                                .build();
                        classNode.getChildren().add(methodNode);
                    });

                    // Option: max_depth >= 2 could include Fields
                    if (maxDepth >= 2) {
                        clazz.getFields().forEach(field -> {
                            CodeNode fieldNode = CodeNode.builder()
                                    .name(field.getVariables().get(0).getNameAsString())
                                    .type("FIELD")
                                    .signature(field.toString().trim())
                                    .startLine(field.getBegin().map(p -> p.line).orElse(0))
                                    .endLine(field.getEnd().map(p -> p.line).orElse(0))
                                    .build();
                            classNode.getChildren().add(fieldNode);
                        });
                    }
                }

                rootNodes.add(classNode);
            });

            return rootNodes.isEmpty() ? fallback(content) : rootNodes;

        } catch (Exception e) {
            // Log warning (skipping actual logger inject for brevity)
            System.err.println("Failed to parse " + filename + " with JavaAstParser: " + e.getMessage());
            return fallback(content);
        }
    }

    private List<CodeNode> fallback(String content) {
        String safeContent = content == null ? "" : content;
        // Truncate to first 2000 chars as per design
        if (safeContent.length() > 2000) {
            safeContent = safeContent.substring(0, 2000) + "\n\n... [TRUNCATED FOR CONTEXT]";
        }
        return List.of(CodeNode.builder()
                .name("RAW_TEXT")
                .type("RAW_TEXT")
                .signature("RAW_TEXT")
                .startLine(1)
                .endLine(safeContent.split("\n").length)
                .children(List.of(CodeNode.builder()
                        .name("Content")
                        .type("CONTENT")
                        .signature(safeContent)
                        .startLine(1)
                        .endLine(safeContent.split("\n").length)
                        .build()))
                .build());
    }
}
