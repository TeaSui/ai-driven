package com.aidriven.core.util;

import com.aidriven.core.exception.*;
import com.aidriven.core.resilience.CircuitBreaker;

import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpResponse;
import java.util.Objects;

/**
 * Utility class for handling HTTP responses and converting error codes to
 * appropriate exceptions.
 */
@Slf4j
public final class HttpResponseHandler {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    @FunctionalInterface
    public interface HttpCallProvider<T> {
        HttpResponse<T> execute() throws Exception;
    }

    private HttpResponseHandler() {
        // Utility class
    }

    /**
     * Checks HTTP response status and throws appropriate exception for error status
     * codes.
     *
     * @param response    The HTTP response to check
     * @param serviceName The name of the service (for error messages)
     * @param operation   The operation being performed (for error messages)
     * @throws UnauthorizedException       if status is 401
     * @throws ForbiddenException          if status is 403
     * @throws NotFoundException           if status is 404
     * @throws RateLimitException          if status is 429
     * @throws ServiceUnavailableException if status is 503
     * @throws HttpClientException         for other 4xx/5xx errors
     */
    public static void checkResponse(HttpResponse<?> response, String serviceName, String operation) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        int statusCode = response.statusCode();
        Object bodyObj = response.body();
        String body = bodyObj instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(bodyObj);

        if (isSuccess(statusCode)) {
            return;
        }

        String context = Objects.nonNull(operation)
                ? String.format("%s - %s", serviceName, operation)
                : serviceName;

        if (statusCode >= 300 && statusCode < 400) {
            throw new HttpClientException(statusCode,
                    String.format(
                            "Unhandled redirect for %s: HTTP %d. Ensure HttpClient is configured to follow redirects.",
                            context, statusCode),
                    body);
        }

        switch (statusCode) {
            case 401 -> throw new HttpClientException(statusCode,
                    String.format("Authentication failed for %s", context), body);
            case 403 -> throw new HttpClientException(statusCode,
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
            case 503 -> throw new HttpClientException(statusCode,
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
     * @return The retry-after value in seconds, or null if not present or
     *         unparseable
     */
    private static Long parseRetryAfter(HttpResponse<?> response) {
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

    /**
     * Executes an HTTP request with exponential backoff for 429 and 5xx errors.
     * Retries up to MAX_RETRIES times. Checks the response after resolving.
     */
    public static <T> HttpResponse<T> sendWithRetry(HttpCallProvider<T> provider, String serviceName, String operation)
            throws Exception {
        int attempt = 0;
        long backoff = INITIAL_BACKOFF_MS;

        while (true) {
            HttpResponse<T> response = provider.execute();
            int statusCode = response.statusCode();

            if (statusCode == 429 || statusCode == 503 || statusCode == 502 || statusCode == 504) {
                attempt++;
                if (attempt > MAX_RETRIES) {
                    return response;
                }

                long waitTime = backoff;
                if (statusCode == 429) {
                    Long retryAfter = parseRetryAfter(response);
                    if (retryAfter != null && retryAfter > 0) {
                        waitTime = retryAfter * 1000;
                    }
                }

                log.warn("{} - {} failed with {}. Retrying in {} ms (attempt {}/{})", serviceName, operation,
                        statusCode, waitTime, attempt, MAX_RETRIES);
                Thread.sleep(waitTime);
                backoff *= 2; // Exponential backoff
            } else {
                return response;
            }
        }
    }

    /**
     * Executes an HTTP request with circuit breaker protection and retry.
     */
    public static <T> HttpResponse<T> sendWithCircuitBreaker(HttpCallProvider<T> provider, String serviceName,
            String operation, CircuitBreaker breaker) throws Exception {
        if (breaker != null && !breaker.allowRequest()) {
            log.warn("CircuitBreaker '{}' is OPEN. Blocking request to {} - {}", breaker.getClass().getSimpleName(),
                    serviceName, operation);
            throw new HttpClientException(503, serviceName + " circuit is OPEN", "Circuit Breaker Protection");
        }

        try {
            HttpResponse<T> response = sendWithRetry(provider, serviceName, operation);
            if (breaker != null) {
                if (isSuccess(response.statusCode())) {
                    breaker.recordSuccess();
                } else if (isServerError(response.statusCode()) || response.statusCode() == 429) {
                    breaker.recordFailure();
                }
            }
            return response;
        } catch (Exception e) {
            if (breaker != null) {
                breaker.recordFailure();
            }
            throw e;
        }
    }
}
