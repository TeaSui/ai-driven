package com.aidriven.claude;

import com.aidriven.core.model.TicketInfo;

/**
 * Utility class for building prompts for AI agents.
 */
public class PromptBuilder {

    private PromptBuilder() {
    }

    /**
     * Builds a system prompt for the backend agent.
     */
    public static String backendAgentSystemPrompt() {
        return """
                You are an expert backend software engineer generating production-ready code.

                CONTEXT UNDERSTANDING:
                You will receive the target project's file tree and relevant source files.
                Study the project structure carefully before generating code:
                - Identify the build system (Maven/Gradle/npm/Go modules)
                - Identify the framework (Spring Boot, Micronaut, Express, etc.)
                - Follow existing package naming conventions and directory structure
                - Reuse existing utilities, base classes, and patterns from the codebase
                - Match the code style, naming conventions, and architectural patterns already in use
                - Place new files in the correct packages/directories based on the project structure
                - Reference existing entities, repositories, services when extending functionality

                RESPONSE FORMAT - START YOUR RESPONSE IMMEDIATELY WITH THE JSON OBJECT:
                {
                    "analysis": "Brief 1-2 sentence analysis of the approach based on existing codebase",
                    "files": [
                        {
                            "path": "src/main/java/com/example/MyClass.java",
                            "content": "package com.example;\\n\\npublic class MyClass {\\n    // code here\\n}",
                            "operation": "CREATE"
                        }
                    ],
                    "commitMessage": "feat: short description",
                    "prTitle": "Short title",
                    "prDescription": "Brief description referencing ticket"
                }

                RULES:
                - Start IMMEDIATELY with { - no preamble, no explanation, no markdown
                - Use "content" field with escaped string (\\n for newlines, \\" for quotes)
                - Keep analysis brief (1-2 sentences max)
                - Generate minimal but complete code that integrates with the existing codebase
                - Match existing patterns: if the project uses Lombok, use Lombok; if it uses records, use records
                - operation: CREATE for new files, UPDATE for modifications
                - Include unit tests following the project's existing test patterns
                """;
    }

    /**
     * Builds a user message from ticket info.
     */
    public static String buildUserMessage(TicketInfo ticket) {
        if (ticket == null) {
            return "Error: TicketInfo is null";
        }

        String key = ticket.getTicketKey() != null ? ticket.getTicketKey() : "Unknown";
        String summary = ticket.getSummary() != null ? ticket.getSummary() : "No Summary";
        String description = ticket.getDescription() != null ? ticket.getDescription() : "No Description";

        java.util.List<String> rawLabels = ticket.getLabels();
        String labels = "";
        if (rawLabels != null) {
            try {
                labels = String.join(", ", rawLabels);
            } catch (Exception e) {
                labels = "Error joining labels: " + e.getMessage();
            }
        }

        String priority = ticket.getPriority() != null ? ticket.getPriority() : "None";

        return String.format("""
                Please implement the following feature:

                **Ticket:** %s
                **Summary:** %s

                **Description:**
                %s

                **Labels:** %s
                **Priority:** %s

                Generate the necessary code to implement this feature.
                """,
                key,
                summary,
                description,
                labels,
                priority);
    }
}
