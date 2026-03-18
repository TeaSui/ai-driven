package com.aidriven.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HmacWebhookFilterTest {

    private static final String TEST_SECRET = "test-webhook-secret-key";
    private static final String TEST_BODY = "{\"action\":\"opened\",\"issue\":{\"number\":1}}";

    @Mock
    private FilterChain filterChain;

    @Test
    void should_throw_when_url_pattern_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new HmacWebhookFilter(null, "header", () -> "secret"))
                .withMessage("urlPattern must not be null");
    }

    @Test
    void should_throw_when_header_name_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new HmacWebhookFilter("/test/**", null, () -> "secret"))
                .withMessage("headerName must not be null");
    }

    @Test
    void should_throw_when_secret_provider_is_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new HmacWebhookFilter("/test/**", "header", null))
                .withMessage("secretProvider must not be null");
    }

    @Test
    void should_pass_valid_hmac_signature() throws Exception {
        HmacWebhookFilter filter = new HmacWebhookFilter(
                "/webhooks/github/**", "X-Hub-Signature-256", () -> TEST_SECRET);

        String signature = computeHmacSignature(TEST_BODY, TEST_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github/agent");
        request.setContent(TEST_BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Hub-Signature-256", signature);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void should_reject_invalid_hmac_signature() throws Exception {
        HmacWebhookFilter filter = new HmacWebhookFilter(
                "/webhooks/github/**", "X-Hub-Signature-256", () -> TEST_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github/agent");
        request.setContent(TEST_BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Hub-Signature-256", "sha256=invalidsignature");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void should_reject_missing_signature_header() throws Exception {
        HmacWebhookFilter filter = new HmacWebhookFilter(
                "/webhooks/github/**", "X-Hub-Signature-256", () -> TEST_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github/agent");
        request.setContent(TEST_BODY.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void should_skip_validation_when_secret_not_configured() throws Exception {
        HmacWebhookFilter filter = new HmacWebhookFilter(
                "/webhooks/github/**", "X-Hub-Signature-256", () -> null);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github/agent");
        request.setContent(TEST_BODY.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void should_skip_non_matching_url_pattern() throws Exception {
        HmacWebhookFilter filter = new HmacWebhookFilter(
                "/webhooks/github/**", "X-Hub-Signature-256", () -> TEST_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        boolean shouldNotFilter = filter.shouldNotFilter(request);

        assertThat(shouldNotFilter).isTrue();
    }

    @Test
    void should_match_url_pattern() throws Exception {
        HmacWebhookFilter filter = new HmacWebhookFilter(
                "/webhooks/github/**", "X-Hub-Signature-256", () -> TEST_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github/agent");

        boolean shouldNotFilter = filter.shouldNotFilter(request);

        assertThat(shouldNotFilter).isFalse();
    }

    @Test
    void should_pass_valid_token_in_token_mode() throws Exception {
        String expectedToken = "jira-pre-shared-token-123";
        HmacWebhookFilter filter = HmacWebhookFilter.forTokenValidation(
                "/webhooks/jira/**", "X-Jira-Webhook-Token", () -> expectedToken);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/jira/agent");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Jira-Webhook-Token", expectedToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void should_reject_invalid_token_in_token_mode() throws Exception {
        HmacWebhookFilter filter = HmacWebhookFilter.forTokenValidation(
                "/webhooks/jira/**", "X-Jira-Webhook-Token", () -> "correct-token");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/jira/agent");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Jira-Webhook-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void should_strip_bearer_prefix_in_token_mode() throws Exception {
        String expectedToken = "jira-token-456";
        HmacWebhookFilter filter = HmacWebhookFilter.forTokenValidation(
                "/webhooks/jira/**", "Authorization", () -> expectedToken);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/jira/agent");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer " + expectedToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void should_return_json_error_body_on_rejection() throws Exception {
        HmacWebhookFilter filter = new HmacWebhookFilter(
                "/webhooks/github/**", "X-Hub-Signature-256", () -> TEST_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github/agent");
        request.setContent(TEST_BODY.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    private static String computeHmacSignature(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));

        StringBuilder hexString = new StringBuilder("sha256=");
        for (byte b : hash) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }
}
