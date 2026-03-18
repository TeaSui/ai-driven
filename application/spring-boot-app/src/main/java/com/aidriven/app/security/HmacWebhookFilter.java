package com.aidriven.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A reusable {@link OncePerRequestFilter} that validates webhook signatures
 * using HMAC-SHA256 or pre-shared token comparison.
 *
 * <p>This filter supports two validation strategies:
 * <ul>
 *   <li><b>HMAC-SHA256 (GitHub, Bitbucket):</b> Computes HMAC of the request body
 *       using the configured secret and compares it (constant-time) against the
 *       signature header value. The header value must be prefixed with {@code sha256=}.</li>
 *   <li><b>Pre-shared token (Jira):</b> Directly compares the header value against
 *       the secret using constant-time comparison. Use {@link #forTokenValidation}
 *       factory method for this mode.</li>
 * </ul>
 *
 * <p>If validation fails, the filter returns HTTP 401 with a JSON error body.
 * The request body is never consumed (uses {@link ContentCachingRequestWrapper}
 * so downstream controllers can still read it).
 *
 * <p><b>Security considerations:</b>
 * <ul>
 *   <li>Uses {@link MessageDigest#isEqual} for constant-time comparison (timing-safe)</li>
 *   <li>Secret is resolved lazily via a {@link Supplier} to support Secrets Manager rotation</li>
 *   <li>Does not log secrets or full signature values</li>
 * </ul>
 */
@Slf4j
public class HmacWebhookFilter extends OncePerRequestFilter {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final String urlPattern;
    private final String headerName;
    private final Supplier<String> secretProvider;
    private final boolean hmacMode;

    /**
     * Creates a filter for HMAC-SHA256 signature validation (GitHub/Bitbucket style).
     *
     * @param urlPattern     Ant-style URL pattern to match (e.g., "/webhooks/github/**")
     * @param headerName     the HTTP header containing the signature (e.g., "X-Hub-Signature-256")
     * @param secretProvider supplies the HMAC secret (lazy, supports rotation)
     */
    public HmacWebhookFilter(String urlPattern, String headerName, Supplier<String> secretProvider) {
        this(urlPattern, headerName, secretProvider, true);
    }

    private HmacWebhookFilter(String urlPattern, String headerName,
                               Supplier<String> secretProvider, boolean hmacMode) {
        this.urlPattern = Objects.requireNonNull(urlPattern, "urlPattern must not be null");
        this.headerName = Objects.requireNonNull(headerName, "headerName must not be null");
        this.secretProvider = Objects.requireNonNull(secretProvider, "secretProvider must not be null");
        this.hmacMode = hmacMode;
    }

    /**
     * Creates a filter for pre-shared token validation (Jira style).
     *
     * <p>Instead of computing an HMAC, this mode compares the header value
     * directly against the secret using constant-time comparison.
     *
     * @param urlPattern     Ant-style URL pattern to match
     * @param headerName     the HTTP header containing the token
     * @param secretProvider supplies the expected token value
     * @return a filter configured for token comparison mode
     */
    public static HmacWebhookFilter forTokenValidation(
            String urlPattern, String headerName, Supplier<String> secretProvider) {
        return new HmacWebhookFilter(urlPattern, headerName, secretProvider, false);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        return !PATH_MATCHER.match(urlPattern, requestPath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String secret = secretProvider.get();
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook secret not configured for pattern '{}', skipping validation", urlPattern);
            filterChain.doFilter(request, response);
            return;
        }

        String headerValue = request.getHeader(headerName);
        if (headerValue == null || headerValue.isBlank()) {
            log.warn("Missing webhook header '{}' for request to {}", headerName, request.getRequestURI());
            sendUnauthorized(response, "Missing webhook signature header");
            return;
        }

        if (hmacMode) {
            validateHmacSignature(request, response, filterChain, secret, headerValue);
        } else {
            validateToken(response, filterChain, request, secret, headerValue);
        }
    }

    private void validateHmacSignature(HttpServletRequest request,
                                       HttpServletResponse response,
                                       FilterChain filterChain,
                                       String secret,
                                       String signatureHeader) throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest =
                request instanceof ContentCachingRequestWrapper cached
                        ? cached
                        : new ContentCachingRequestWrapper(request);

        // Read the body through the wrapper so it remains available downstream
        wrappedRequest.getInputStream().readAllBytes();
        byte[] body = wrappedRequest.getContentAsByteArray();

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(body);

            String computed = SIGNATURE_PREFIX + bytesToHex(hash);

            if (!MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8))) {
                log.warn("HMAC signature mismatch for request to {}", request.getRequestURI());
                sendUnauthorized(response, "Invalid webhook signature");
                return;
            }

            log.debug("HMAC signature verified for request to {}", request.getRequestURI());
            filterChain.doFilter(wrappedRequest, response);
        } catch (Exception e) {
            log.error("Failed to verify HMAC signature: {}", e.getMessage());
            sendUnauthorized(response, "Signature verification failed");
        }
    }

    private void validateToken(HttpServletResponse response,
                               FilterChain filterChain,
                               HttpServletRequest request,
                               String expectedToken,
                               String receivedToken) throws ServletException, IOException {
        String tokenValue = receivedToken;

        // Strip "Bearer " prefix if present (Jira Authorization header)
        if (tokenValue.toLowerCase().startsWith("bearer ")) {
            tokenValue = tokenValue.substring(7).strip();
        }

        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                tokenValue.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Token mismatch for request to {}", request.getRequestURI());
            sendUnauthorized(response, "Invalid webhook token");
            return;
        }

        log.debug("Token verified for request to {}", request.getRequestURI());
        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}}");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }
}
