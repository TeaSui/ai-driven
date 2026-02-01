package com.aidriven.core.util;

import com.aidriven.core.exception.ConflictException;
import com.aidriven.core.exception.HttpClientException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpResponseHandlerConflictTest {

    @Test
    void should_throw_ConflictException_for_409_status() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(409);
        when(response.body()).thenReturn("{\"error\": \"branch already exists\"}");

        ConflictException exception = assertThrows(ConflictException.class,
                () -> HttpResponseHandler.checkResponse(response, "Bitbucket", "createBranch"));

        assertEquals(409, exception.getStatusCode());
        assertTrue(exception.getMessage().contains("Resource conflict"));
        assertTrue(exception.getMessage().contains("Bitbucket"));
        assertTrue(exception.getMessage().contains("createBranch"));
    }

    @Test
    void should_include_response_body_in_ConflictException() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(409);
        String responseBody = "{\"type\": \"error\", \"message\": \"Conflict detected\"}";
        when(response.body()).thenReturn(responseBody);

        ConflictException exception = assertThrows(ConflictException.class,
                () -> HttpResponseHandler.checkResponse(response, "Service", "operation"));

        assertEquals(responseBody, exception.getResponseBody());
    }

    @Test
    void should_not_throw_for_200_status() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");

        // Should not throw
        HttpResponseHandler.checkResponse(response, "Service", "operation");
    }

    @Test
    void should_not_throw_for_201_status() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn("{}");

        // Should not throw
        HttpResponseHandler.checkResponse(response, "Service", "operation");
    }

    @Test
    void should_throw_generic_HttpClientException_for_other_4xx() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("Bad request");

        HttpClientException exception = assertThrows(HttpClientException.class,
                () -> HttpResponseHandler.checkResponse(response, "Service", "operation"));

        assertEquals(400, exception.getStatusCode());
    }

}
