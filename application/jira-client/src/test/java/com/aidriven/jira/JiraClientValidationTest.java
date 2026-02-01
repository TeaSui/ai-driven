package com.aidriven.jira;

import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for JiraClient input validation.
 * Validates null checks, empty strings, and invalid formats.
 */
class JiraClientValidationTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private JiraClient jiraClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        jiraClient = new JiraClient("https://test.atlassian.net", "test@test.com", "token");
        try {
            var field = JiraClient.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(jiraClient, mockHttpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock HttpClient", e);
        }
    }

    @Test
    void should_throw_null_pointer_exception_for_null_ticket_key_in_get_ticket() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            jiraClient.getTicket(null);
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_ticket_key_in_update_status() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            jiraClient.updateStatus(null, "Done");
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_status_in_update_status() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            jiraClient.updateStatus("PROJ-123", null);
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_ticket_key_in_add_comment() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            jiraClient.addComment(null, "Test comment");
        });
    }

    @Test
    void should_throw_null_pointer_exception_for_null_comment_in_add_comment() {
        // When/Then: Verify NullPointerException is thrown
        assertThrows(NullPointerException.class, () -> {
            jiraClient.addComment("PROJ-123", null);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "123", "PROJ", "proj-123", "PROJ_123", "PROJ-", "-123"})
    void should_throw_illegal_argument_exception_for_invalid_ticket_key_format(String invalidKey) {
        // When/Then: Verify IllegalArgumentException is thrown for invalid formats
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            jiraClient.getTicket(invalidKey);
        });

        assertTrue(exception.getMessage().contains("Invalid ticket key format"));
    }

    @Test
    void should_accept_valid_ticket_key_formats() throws Exception {
        // Given: Mock successful response
        String validResponse = """
                {
                    "id": "12345",
                    "key": "PROJ-123",
                    "fields": {
                        "summary": "Test ticket",
                        "description": null,
                        "status": {"name": "Open"},
                        "labels": []
                    }
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(validResponse);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: Call with valid ticket keys
        TicketInfo ticket1 = jiraClient.getTicket("PROJ-123");
        TicketInfo ticket2 = jiraClient.getTicket("AB-1");
        TicketInfo ticket3 = jiraClient.getTicket("MYPROJECT-9999");

        // Then: No exceptions thrown
        assertNotNull(ticket1);
        assertEquals("PROJ-123", ticket1.getTicketKey());
    }

    @Test
    void should_throw_illegal_state_exception_when_transition_not_found() throws Exception {
        // Given: Mock transitions response without target status
        String transitionsResponse = """
                {
                    "transitions": [
                        {
                            "id": "11",
                            "name": "To Do",
                            "to": {"name": "To Do"}
                        },
                        {
                            "id": "21",
                            "name": "In Progress",
                            "to": {"name": "In Progress"}
                        }
                    ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(transitionsResponse);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify IllegalStateException is thrown
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            jiraClient.updateStatus("PROJ-123", "Done");
        });

        assertTrue(exception.getMessage().contains("Transition to 'Done' not available"));
        assertTrue(exception.getMessage().contains("PROJ-123"));
    }

    @Test
    void should_handle_empty_transitions_list() throws Exception {
        // Given: Mock empty transitions response
        String transitionsResponse = """
                {
                    "transitions": []
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(transitionsResponse);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify IllegalStateException is thrown
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            jiraClient.updateStatus("PROJ-123", "Done");
        });

        assertTrue(exception.getMessage().contains("Transition to 'Done' not available"));
    }

    @Test
    void should_match_transition_case_insensitively() throws Exception {
        // Given: Mock transitions response
        String transitionsResponse = """
                {
                    "transitions": [
                        {
                            "id": "31",
                            "name": "Done",
                            "to": {"name": "Done"}
                        }
                    ]
                }
                """;

        // Each updateStatus makes 2 HTTP requests: getTransitions + transitionTicket
        // getTransitions: checkResponse reads statusCode()+body(), then readTree reads body() again
        // transitionTicket: reads statusCode() twice (line 107 + line 110), no body() if 204
        // So per call: statusCode() x3, body() x2
        when(mockResponse.statusCode()).thenReturn(
                200,       // checkResponse in getTransitions (1st call)
                204, 204,  // transitionTicket statusCode checks (1st call)
                200,       // checkResponse in getTransitions (2nd call)
                204, 204   // transitionTicket statusCode checks (2nd call)
        );
        when(mockResponse.body()).thenReturn(
                transitionsResponse, transitionsResponse,  // checkResponse + readTree in getTransitions (1st call)
                transitionsResponse, transitionsResponse   // checkResponse + readTree in getTransitions (2nd call)
        );
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When: Call with different case - lowercase
        assertDoesNotThrow(() -> {
            jiraClient.updateStatus("PROJ-123", "done");
        });

        // When: Call with different case - uppercase
        assertDoesNotThrow(() -> {
            jiraClient.updateStatus("PROJ-123", "DONE");
        });
    }
}
