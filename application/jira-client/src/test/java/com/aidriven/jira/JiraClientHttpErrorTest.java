package com.aidriven.jira;

import com.aidriven.core.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for JiraClient HTTP error code handling.
 * Validates that appropriate exceptions are thrown for different HTTP error
 * responses.
 */
class JiraClientHttpErrorTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private JiraClient jiraClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Use reflection to inject the mock HttpClient
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
    void should_throw_unauthorized_exception_on_401() throws Exception {
        // Given: Mock 401 response
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("{\"errorMessages\":[\"Invalid credentials\"]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify HttpClientException is thrown
        HttpClientException exception = assertThrows(HttpClientException.class, () -> {
            jiraClient.getTicket("PROJ-123");
        });

        assertTrue(exception.getMessage().contains("Authentication failed"));
        assertEquals(401, exception.getStatusCode());
    }

    @Test
    void should_throw_forbidden_exception_on_403() throws Exception {
        // Given: Mock 403 response
        when(mockResponse.statusCode()).thenReturn(403);
        when(mockResponse.body()).thenReturn("{\"errorMessages\":[\"Access denied\"]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify HttpClientException is thrown
        HttpClientException exception = assertThrows(HttpClientException.class, () -> {
            jiraClient.getTicket("PROJ-123");
        });

        assertTrue(exception.getMessage().contains("Access denied"));
        assertEquals(403, exception.getStatusCode());
    }

    @Test
    void should_throw_not_found_exception_on_404() throws Exception {
        // Given: Mock 404 response
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockResponse.body()).thenReturn("{\"errorMessages\":[\"Issue does not exist\"]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify NotFoundException is thrown
        NotFoundException exception = assertThrows(NotFoundException.class, () -> {
            jiraClient.getTicket("PROJ-999");
        });

        assertTrue(exception.getMessage().contains("Resource not found"));
        assertEquals(404, exception.getStatusCode());
    }

    @Test
    void should_throw_rate_limit_exception_on_429() throws Exception {
        // Given: Mock 429 response with Retry-After header
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn("{\"errorMessages\":[\"Rate limit exceeded\"]}");
        when(mockResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(
                java.util.Map.of("Retry-After", java.util.List.of("60")),
                (name, value) -> true));
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify RateLimitException is thrown
        RateLimitException exception = assertThrows(RateLimitException.class, () -> {
            jiraClient.getTicket("PROJ-123");
        });

        assertTrue(exception.getMessage().contains("Rate limit exceeded"));
        assertEquals(429, exception.getStatusCode());
        assertEquals(60L, exception.getRetryAfter());
    }

    @Test
    void should_throw_http_client_exception_on_500() throws Exception {
        // Given: Mock 500 response
        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("{\"errorMessages\":[\"Internal server error\"]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify HttpClientException is thrown
        HttpClientException exception = assertThrows(HttpClientException.class, () -> {
            jiraClient.getTicket("PROJ-123");
        });

        assertTrue(exception.getMessage().contains("Server error"));
        assertEquals(500, exception.getStatusCode());
    }

    @Test
    void should_throw_service_unavailable_exception_on_503() throws Exception {
        // Given: Mock 503 response
        when(mockResponse.statusCode()).thenReturn(503);
        when(mockResponse.body()).thenReturn("{\"errorMessages\":[\"Service unavailable\"]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify HttpClientException is thrown
        HttpClientException exception = assertThrows(HttpClientException.class, () -> {
            jiraClient.getTicket("PROJ-123");
        });

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
        assertEquals(503, exception.getStatusCode());
    }
}
