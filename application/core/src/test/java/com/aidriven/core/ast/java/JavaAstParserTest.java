package com.aidriven.core.ast.java;

import com.aidriven.core.ast.CodeNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaAstParserTest {

    private final JavaAstParser parser = new JavaAstParser();

    @Test
    void testParseValidJavaClass() {
        String javaCode = """
                package com.example;

                public class MyService {
                    private final String myField = "test";

                    public void doSomething(int x) {
                        System.out.println("hello");
                    }

                    private int calculate() {
                        return 42;
                    }
                }
                """;

        // depth 1 = class + methods
        List<CodeNode> nodes = parser.parse("MyService.java", javaCode, 1);

        assertEquals(1, nodes.size(), "Should parse exactly 1 root class");
        CodeNode root = nodes.get(0);
        assertEquals("MyService", root.getName());
        assertEquals("CLASS", root.getType());
        assertEquals("class MyService", root.getSignature());

        List<CodeNode> methods = root.getChildren();
        assertEquals(2, methods.size(), "Should parse 2 methods");

        CodeNode m1 = methods.get(0);
        assertEquals("doSomething", m1.getName());
        assertEquals("public void doSomething(int x)", m1.getSignature());

        CodeNode m2 = methods.get(1);
        assertEquals("calculate", m2.getName());
        assertEquals("private int calculate()", m2.getSignature());
    }

    @Test
    void testParseMaxDepthZeroOnlyExtractsClass() {
        String javaCode = """
                public class QuickClass {
                    public void fast() {}
                }
                """;

        List<CodeNode> nodes = parser.parse("QuickClass.java", javaCode, 0);

        assertEquals(1, nodes.size());
        assertEquals("QuickClass", nodes.get(0).getName());
        assertTrue(nodes.get(0).getChildren().isEmpty(), "With maxDepth=0, methods should be ignored");
    }

    @Test
    void testParseNonJavaFileFallsBackToRawText() {
        String jsCode = "function hello() { return 'world'; }";

        List<CodeNode> nodes = parser.parse("script.js", jsCode, 1);

        assertEquals(1, nodes.size());
        CodeNode fallback = nodes.get(0);
        assertEquals("RAW_TEXT", fallback.getType());

        List<CodeNode> lines = fallback.getChildren();
        assertEquals(1, lines.size());
        assertEquals(jsCode, lines.get(0).getSignature());
    }
}
