package com.aidriven.lambda;

import com.aidriven.core.config.AppConfig;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FetchTicketHandlerTest {

    @Mock
    private TicketStateRepository ticketStateRepository;

    @Mock
    private JiraClient jiraClient;

    @Mock
    private Context lambdaContext;

    @Mock
    private AppConfig appConfig;

    private FetchTicketHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(appConfig.getDefaultPlatform()).thenReturn("BITBUCKET");
        when(appConfig.getDefaultWorkspace()).thenReturn(null);
        when(appConfig.getDefaultRepo()).thenReturn(null);
        handler = new FetchTicketHandler(ticketStateRepository, jiraClient, appConfig);
    }

    @Test
    void should_fetch_ticket_and_return_details() throws Exception {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-1")
                .summary("Summary")
                .description("Description")
                .labels(List.of("backend")) // Defaults to BACKEND agent
                .priority("High")
                .build();

        when(jiraClient.getTicket("PROJ-1")).thenReturn(ticket);

        Map<String, Object> input = Map.of("ticketKey", "PROJ-1", "ticketId", "123");
        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("PROJ-1", result.get("ticketKey"));
        assertEquals("Summary", result.get("summary"));
        assertNotNull(result.get("agentType"));
        // resolvedModel defaults to null
        assertFalse(result.containsKey("resolvedModel"));

        verify(ticketStateRepository).save(any(TicketState.class));
    }

    @Test
    void should_resolve_model_from_labels() throws Exception {
        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-2")
                .summary("Summary")
                .description("Description")
                .labels(List.of("ai-model:sonnet"))
                .build();

        when(jiraClient.getTicket("PROJ-2")).thenReturn(ticket);

        Map<String, Object> input = Map.of("ticketKey", "PROJ-2", "ticketId", "123");
        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("claude-sonnet-4-5", result.get("resolvedModel"));
    }

    @Test
    void should_use_configured_default_platform() throws Exception {
        // Override setup with GITHUB default
        when(appConfig.getDefaultPlatform()).thenReturn("GITHUB");
        handler = new FetchTicketHandler(ticketStateRepository, jiraClient, appConfig);

        TicketInfo ticket = TicketInfo.builder()
                .ticketKey("PROJ-4")
                .summary("Summary")
                .description("Description")
                .labels(List.of("backend")) // No platform label
                .build();

        when(jiraClient.getTicket("PROJ-4")).thenReturn(ticket);

        Map<String, Object> input = Map.of("ticketKey", "PROJ-4", "ticketId", "123");
        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals("GITHUB", result.get("platform"));
    }

    @Test
    void should_propagate_jira_error() throws Exception {
        when(jiraClient.getTicket("PROJ-3")).thenThrow(new RuntimeException("Jira down"));

        Map<String, Object> input = Map.of("ticketKey", "PROJ-3", "ticketId", "123");

        assertThrows(RuntimeException.class, () -> handler.handleRequest(input, lambdaContext));
    }
}
