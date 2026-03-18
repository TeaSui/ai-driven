package com.aidriven.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK v2 client beans.
 *
 * <p>Replaces {@code AwsClientFactory}'s lazy ConcurrentHashMap cache with
 * Spring singleton scope (default). Each client is created once at startup
 * and reused across requests, matching the previous Lambda container behavior.
 *
 * <p>All clients are thread-safe and manage their own connection pools.
 */
@Configuration
public class AwsConfig {

    @Bean
    DynamoDbClient dynamoDbClient(AppProperties properties) {
        return DynamoDbClient.builder()
                .region(Region.of(properties.aws().region()))
                .build();
    }

    @Bean
    S3Client s3Client(AppProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.aws().region()))
                .build();
    }

    @Bean
    SqsClient sqsClient(AppProperties properties) {
        return SqsClient.builder()
                .region(Region.of(properties.aws().region()))
                .build();
    }

    @Bean
    SfnClient sfnClient(AppProperties properties) {
        return SfnClient.builder()
                .region(Region.of(properties.aws().region()))
                .build();
    }

    @Bean
    SecretsManagerClient secretsManagerClient(AppProperties properties) {
        return SecretsManagerClient.builder()
                .region(Region.of(properties.aws().region()))
                .build();
    }
}
