package com.aidriven.core.audit;

import com.aidriven.core.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for recording AI invocations (prompt, response, metadata)
 * to an S3 bucket for compliance and audit-trail purposes.
 * If S3 upload fails, it fails "open" by logging to CloudWatch Logs.
 */
@Slf4j
public class AuditService {

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String auditBucketName;

    public AuditService(S3Client s3Client, ObjectMapper objectMapper, AppConfig appConfig) {
        this(s3Client, objectMapper, System.getenv("AUDIT_BUCKET_NAME"));
    }

    public AuditService(S3Client s3Client, ObjectMapper objectMapper, String auditBucketName) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.auditBucketName = auditBucketName;
    }

    /**
     * Records a complete AI invocation to the audit trail.
     *
     * @param ticketKey     The Jira ticket key (e.g. CRM-123)
     * @param systemPrompt  The composed system prompt
     * @param userPrompt    The composed user prompt
     * @param modelResponse The raw textual response from the model
     * @param metadata      Additional telemetry like token usage, model name,
     *                      duration
     */
    public void recordInvocation(String ticketKey, String systemPrompt, String userPrompt,
            String modelResponse, Map<String, Object> metadata) {

        if (auditBucketName == null || auditBucketName.isBlank()) {
            log.warn("AUDIT_BUCKET_NAME is not configured. Audit trail will only go to CloudWatch.");
            logFallback(ticketKey, systemPrompt, userPrompt, modelResponse, metadata);
            return;
        }

        try {
            Instant now = Instant.now();
            String year = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC).format(now);
            String month = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC).format(now);
            String invocationId = UUID.randomUUID().toString();

            // Format: audit/2023/10/CRM-123/1698144000_abc123/
            String prefix = String.format("audit/%s/%s/%s/%d_%s/",
                    year, month, ticketKey, now.getEpochSecond(), invocationId);

            // 1. Upload Prompts
            uploadText(prefix + "system_prompt.txt", systemPrompt != null ? systemPrompt : "");
            uploadText(prefix + "user_prompt.txt", userPrompt != null ? userPrompt : "");

            // 2. Upload Response
            uploadText(prefix + "model_response.txt", modelResponse != null ? modelResponse : "");

            // 3. Upload Metadata (JSON)
            Map<String, Object> enrichedMetadata = new HashMap<>(metadata != null ? metadata : Map.of());
            enrichedMetadata.put("timestamp", now.toString());
            enrichedMetadata.put("ticketKey", ticketKey);
            enrichedMetadata.put("invocationId", invocationId);

            String metadataJson = objectMapper.writeValueAsString(enrichedMetadata);
            uploadText(prefix + "metadata.json", metadataJson);

            log.info("Successfully recorded audit trail to s3://{}/{}", auditBucketName, prefix);

        } catch (Exception e) {
            log.error("Failed to upload audit trail to S3. Emitting CloudWatch metric and falling back to logs.", e);
            // TODO: In a real environment with CloudWatch Metrics injected, we would emit
            // `AuditUploadFailure` here.
            logFallback(ticketKey, systemPrompt, userPrompt, modelResponse, metadata);
        }
    }

    private void uploadText(String key, String content) {
        PutObjectRequest putObj = PutObjectRequest.builder()
                .bucket(auditBucketName)
                .key(key)
                .contentType(key.endsWith(".json") ? "application/json" : "text/plain")
                .build();
        s3Client.putObject(putObj, RequestBody.fromString(content));
    }

    private void logFallback(String ticketKey, String systemPrompt, String userPrompt,
            String modelResponse, Map<String, Object> metadata) {
        try {
            Map<String, Object> fallbackPayload = new HashMap<>();
            fallbackPayload.put("ticketKey", ticketKey);
            fallbackPayload.put("metadata", metadata);
            fallbackPayload.put("systemPromptLength", systemPrompt != null ? systemPrompt.length() : 0);
            fallbackPayload.put("userPromptLength", userPrompt != null ? userPrompt.length() : 0);
            fallbackPayload.put("responseLength", modelResponse != null ? modelResponse.length() : 0);

            // We log the JSON structured summary to CloudWatch so it can still be queried
            // via Logs Insights
            log.warn("AUDIT_TRAIL_FALLBACK: {}", objectMapper.writeValueAsString(fallbackPayload));
        } catch (Exception ex) {
            log.error("Failed to serialize audit fallback.", ex);
        }
    }
}
