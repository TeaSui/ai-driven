package com.aidriven.core.notification;

import com.aidriven.spi.notification.ApprovalNotifier.PendingApprovalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlackNotifierTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Captor
    private ArgumentCaptor<HttpRequest> requestCaptor;

    private SlackNotifier notifier;
    private final String webhookUrl = "https://hooks.slack.com/services/T000/B000/XXX";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);

        notifier = new SlackNotifier(httpClient, webhookUrl, Optional.empty());
    }

    @Test
    void should_send_formatted_message_to_slack() throws Exception {
        PendingApprovalContext context = new PendingApprovalContext(
                "PROJ-123",
                "source_control_merge_pr",
                "Merge PR #456 into main",
                "claude-sonnet-4-6",
                "Auto-merge after tests pass",
                86400L);

        notifier.notifyPending(context);

        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();

        assertEquals("POST", request.method());
        assertEquals(webhookUrl, request.uri().toString());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(""));

        // Extract body string from the BodyPublisher (it's somewhat encapsulated in
        // modern Java HttpClient)
        // For testing, we verify the JSON payload string is generated correctly in the
        // notifier implementation.
        // Since we can't easily extract the string from HttpRequest.BodyPublisher
        // without subscribing,
        // we'll rely on a package-private helper in SlackNotifier to assert the payload
        // explicitly.
        String payload = notifier.buildPayload(context);

        assertTrue(payload.contains("PROJ-123"));
        assertTrue(payload.contains("Merge PR #456 into main"));
        assertTrue(payload.contains("claude-sonnet-4-6"));
        assertTrue(payload.contains("@ai approve"));
        assertTrue(payload.contains("@ai reject"));
    }
}
