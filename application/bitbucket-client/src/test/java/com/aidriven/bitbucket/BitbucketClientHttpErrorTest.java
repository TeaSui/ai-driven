package com.aidriven.bitbucket;

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
 * Tests for BitbucketClient HTTP error code handling.
 * Validates that appropriate exceptions are thrown for different HTTP error responses.
 */
class BitbucketClientHttpErrorTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private BitbucketClient bitbucketClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bitbucketClient = new BitbucketClient("workspace", "repo", "user", "pass");
        try {
            var field = BitbucketClient.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(bitbucketClient, mockHttpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock HttpClient", e);
        }
    }

    @Test
    void should_throw_unauthorized_exception_on_401_for_create_branch() throws Exception {
        // Given: Mock 401 response
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("{\"error\":{\"message\":\"Invalid credentials\"}}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify UnauthorizedException is thrown
        UnauthorizedException exception = assertThrows(UnauthorizedException.class, () -> {
            // This will call getBranchCommitHash internally which will fail
            bitbucketClient.createBranch("feature", "main");
        });

        assertEquals(401, exception.getStatusCode());
    }

    @Test
    void should_throw_forbidden_exception_on_403() throws Exception {
        // Given: Mock 403 response
        when(mockResponse.statusCode()).thenReturn(403);
        when(mockResponse.body()).thenReturn("{\"error\":{\"message\":\"Access denied\"}}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify ForbiddenException is thrown
        ForbiddenException exception = assertThrows(ForbiddenException.class, () -> {
            bitbucketClient.getDefaultBranch();
        });

        assertEquals(403, exception.getStatusCode());
    }

    @Test
    void should_throw_not_found_exception_on_404_for_branch() throws Exception {
        // Given: Mock 404 response
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockResponse.body()).thenReturn("{\"error\":{\"message\":\"Branch not found\"}}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify NotFoundException is thrown
        NotFoundException exception = assertThrows(NotFoundException.class, () -> {
            bitbucketClient.createBranch("feature", "nonexistent-branch");
        });

        assertEquals(404, exception.getStatusCode());
    }

    @Test
    void should_throw_conflict_exception_on_409() throws Exception {
        // Given: Mock responses for getBranchCommitHash (200) then createBranch (409)
        // Note: checkResponse reads body() even for success path, plus the caller reads it too
        // So getBranchCommitHash consumes 2 body() calls, then createBranch consumes 2 more
        when(mockResponse.statusCode()).thenReturn(200, 200, 409, 409);
        when(mockResponse.body()).thenReturn(
                "{\"target\":{\"hash\":\"abc123\"}}",  // checkResponse in getBranchCommitHash (success, ignored)
                "{\"target\":{\"hash\":\"abc123\"}}",  // objectMapper.readTree in getBranchCommitHash
                "{\"error\":{\"message\":\"Branch already exists\"}}",  // statusCode check in createBranch
                "{\"error\":{\"message\":\"Branch already exists\"}}"   // checkResponse body read
        );
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify ConflictException (extends HttpClientException) is thrown
        ConflictException exception = assertThrows(ConflictException.class, () -> {
            bitbucketClient.createBranch("existing-branch", "main");
        });

        assertEquals(409, exception.getStatusCode());
        assertTrue(exception.getMessage().contains("conflict"));
    }

    @Test
    void should_throw_rate_limit_exception_on_429() throws Exception {
        // Given: Mock 429 response
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn("{\"error\":{\"message\":\"Rate limit exceeded\"}}");
        when(mockResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(
                java.util.Map.of("Retry-After", java.util.List.of("120")),
                (name, value) -> true
        ));
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify RateLimitException is thrown
        RateLimitException exception = assertThrows(RateLimitException.class, () -> {
            bitbucketClient.getDefaultBranch();
        });

        assertEquals(429, exception.getStatusCode());
        assertEquals(120L, exception.getRetryAfter());
    }

    @Test
    void should_throw_service_unavailable_exception_on_503() throws Exception {
        // Given: Mock 503 response
        when(mockResponse.statusCode()).thenReturn(503);
        when(mockResponse.body()).thenReturn("{\"error\":{\"message\":\"Service unavailable\"}}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        // When/Then: Verify ServiceUnavailableException is thrown
        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class, () -> {
            bitbucketClient.getDefaultBranch();
        });

        assertEquals(503, exception.getStatusCode());
    }
}
