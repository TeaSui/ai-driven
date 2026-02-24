package com.aidriven.lambda.factory;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lazy-initialised factory for AWS SDK v2 clients.
 *
 * <p>Each client is created exactly once (per JVM/Lambda container) and reused
 * across invocations. The underlying SDK clients manage their own connection
 * pools and are thread-safe.
 *
 * <p>Extracted from {@link ServiceFactory} to satisfy SRP: AWS client lifecycle
 * management is a distinct concern from domain service wiring.
 */
public class AwsClientFactory {

    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> supplier) {
        return (T) cache.computeIfAbsent(key, k -> supplier.get());
    }

    public DynamoDbClient dynamoDb() {
        return cached("DynamoDbClient", DynamoDbClient::create);
    }

    public SecretsManagerClient secretsManager() {
        return cached("SecretsManagerClient", SecretsManagerClient::create);
    }

    public S3Client s3() {
        return cached("S3Client", S3Client::create);
    }

    public SqsClient sqs() {
        return cached("SqsClient", SqsClient::create);
    }

    public SfnClient sfn() {
        return cached("SfnClient", SfnClient::create);
    }
}
