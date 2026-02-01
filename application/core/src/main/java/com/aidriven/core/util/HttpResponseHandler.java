package com.aidriven.core.util;

import com.aidriven.core.exception.*;

import java.net.http.HttpResponse;
import java.util.Objects;

/**
 * Utility class for handling HTTP responses and converting error codes to appropriate exceptions.
 */
public final class HttpResponseHandler {

    private HttpResponseHandler() {
        // Utility class
    }

    /**
     * Checks HTTP response status and throws appropriate exception for error status codes.
     *
     * @param response The HTTP response to check
     * @param serviceName The name of the service (for error messages)
     * @param operation The operation being performed (for error messages)
     * @throws UnauthorizedException if status is 401
     * @throws ForbiddenException if status is 403
     * @throws NotFoundException if status is 404
     * @throws RateLimitException if status is 429
     * @throws ServiceUnavailableException if status is 503
     * @throws HttpClientException for other 4xx/5xx errors
     */
    public static void checkResponse(HttpResponse<String> response, String serviceName, String operation) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        int statusCode = response.statusCode();
        String body = response.body();

        if (isSuccess(statusCode)) {
            return;
        }

        String context = Objects.nonNull(operation)
                ? String.format("%s - %s", serviceName, operation)
                : serviceName;

        switch (statusCode) {
            case 401 -> throw new UnauthorizedException(
                    String.format("Authentication failed for %s", context), body);
            case 403 -> throw new ForbiddenException(
                    String.format("Access denied for %s", context), body);
            case 404 -> throw new NotFoundException(
                    String.format("Resource not found for %s", context), body);
            case 409 -> throw new ConflictException(
                    String.format("Resource conflict for %s", context), body);
            case 429 -> {
                Long retryAfter = parseRetryAfter(response);
                throw new RateLimitException(
                        String.format("Rate limit exceeded for %s", context), body, retryAfter);
            }
            case 503 -> throw new ServiceUnavailableException(
                    String.format("%s is temporarily unavailable", serviceName), body);
            default -> {
                if (statusCode >= 400 && statusCode < 500) {
                    throw new HttpClientException(statusCode,
                            String.format("Client error for %s: HTTP %d", context, statusCode), body);
                } else if (statusCode >= 500) {
                    throw new HttpClientException(statusCode,
                            String.format("Server error for %s: HTTP %d", context, statusCode), body);
                }
            }
        }
    }

    /**
     * Checks if the status code indicates success (2xx).
     */
    public static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Checks if the status code indicates a client error (4xx).
     */
    public static boolean isClientError(int statusCode) {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * Checks if the status code indicates a server error (5xx).
     */
    public static boolean isServerError(int statusCode) {
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * Attempts to parse the Retry-After header from the response.
     *
     * @return The retry-after value in seconds, or null if not present or unparseable
     */
    private static Long parseRetryAfter(HttpResponse<String> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }
}
