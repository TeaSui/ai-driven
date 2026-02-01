package com.aidriven.claude;

import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PromptBuilderTest {

    @Test
    public void testBackendAgentSystemPrompt() {
        String prompt = PromptBuilder.backendAgentSystemPrompt();
        assertTrue(prompt.contains("expert backend software engineer"));
        assertTrue(prompt.contains("JSON"));
    }

    @Test
    public void testBuildUserMessage() {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-123")
                .summary("Test Summary")
                .description("Test Description")
                .labels(List.of("backend"))
                .priority("High")
                .build();

        String message = PromptBuilder.buildUserMessage(ticket);
        assertTrue(message.contains("PROJ-123"));
        assertTrue(message.contains("Test Summary"));
        assertTrue(message.contains("Test Description"));
        assertTrue(message.contains("backend"));
    }
}
