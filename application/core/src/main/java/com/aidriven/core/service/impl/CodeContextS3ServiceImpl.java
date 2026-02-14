package com.aidriven.core.service.impl;

import com.aidriven.core.service.ContextStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

/**
 * Service for storing and retrieving code context from S3.
 * This avoids the Step Functions 256KB payload limit by offloading
 * large code context to S3 and passing only the S3 key through the workflow.
 */
@Slf4j
@RequiredArgsConstructor
public class CodeContextS3ServiceImpl implements ContextStorageService {

    private final S3Client s3Client;
    private final String bucketName;

    /**
     * Stores code context in S3 and returns the S3 key.
     *
     * @param ticketKey The Jira ticket key (used as folder prefix)
     * @param content   The full code context text
     * @return The S3 key where the context is stored
     */
    @Override
    public String storeContext(String ticketKey, String content) {

        String key = String.format("context/%s/%d.txt", ticketKey, System.currentTimeMillis());

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));

        log.info("Stored code context for {} at s3://{}/{} ({} chars)", ticketKey, bucketName, key, content.length());
        return key;
    }

    /**
     * Retrieves code context from S3.
     *
     * @param key The S3 key
     * @return The code context text
     */
    @Override
    public String getContext(String key) {
        var response = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build());

        String content = response.asString(StandardCharsets.UTF_8);
        log.info("Retrieved code context from s3://{}/{} ({} chars)", bucketName, key, content.length());
        return content;
    }

}
