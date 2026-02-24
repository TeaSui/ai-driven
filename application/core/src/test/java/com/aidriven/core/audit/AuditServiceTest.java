package com.aidriven.core.audit;

import com.aidriven.core.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private AppConfig appConfig;

    private ObjectMapper objectMapper = new ObjectMapper();
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(s3Client, objectMapper, "my-audit-bucket");
    }

    @Test
    void should_upload_prompts_and_metadata_to_s3() {
        String ticketKey = "CRM-123";
        String systemPrompt = "You are a helpful AI.";
        String userPrompt = "Write me a function.";
        String modelResponse = "Here is the function.";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("model", "claude-sonnet");
        metadata.put("tokensUsed", 150);

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        auditService.recordInvocation(ticketKey, systemPrompt, userPrompt, modelResponse, metadata);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);

        // Expect 4 files: system_prompt.txt, user_prompt.txt, model_response.txt,
        // metadata.json
        verify(s3Client, times(4)).putObject(requestCaptor.capture(), bodyCaptor.capture());

        List<PutObjectRequest> requests = requestCaptor.getAllValues();

        assertThat(requests).allSatisfy(r -> assertThat(r.bucket()).isEqualTo("my-audit-bucket"));
        assertThat(requests.stream().anyMatch(r -> r.key().endsWith("system_prompt.txt"))).isTrue();
        assertThat(requests.stream()
                .anyMatch(r -> r.key().endsWith("metadata.json") && r.contentType().equals("application/json")))
                .isTrue();
    }

    @Test
    void should_gracefully_fallback_to_cloudwatch_if_s3_fails() {
        String ticketKey = "CRM-456";

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("S3 is down"));

        assertThatCode(() -> auditService.recordInvocation(ticketKey, "sys", "usr", "res", Map.of()))
                .doesNotThrowAnyException();

        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void should_fallback_to_cloudwatch_if_bucket_name_not_configured() {
        auditService = new AuditService(s3Client, objectMapper, "");

        auditService.recordInvocation("CRM-789", "sys", "usr", "res", Map.of());

        // S3 client should not be invoked at all
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
