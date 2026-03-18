package com.aidriven.app.config;

import com.aidriven.core.agent.ConversationRepository;
import com.aidriven.core.agent.DynamoConversationRepository;
import com.aidriven.core.audit.AuditService;
import com.aidriven.core.cost.BudgetTracker;
import com.aidriven.core.observability.CloudWatchObservabilityClient;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.security.DynamoDbRateLimiter;
import com.aidriven.core.security.RateLimiter;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.service.impl.SecretsServiceImpl;
import com.aidriven.spi.observability.ObservabilityClient;
import com.aidriven.tool.observability.ObservabilityToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Core service beans: secrets, repositories, idempotency, audit, storage, budget, rate limiting.
 *
 * <p>Replaces the direct-wiring section of {@code ServiceFactory} that creates
 * persistence and utility services. All beans are singletons (default scope),
 * matching the ConcurrentHashMap cache pattern of the original factory.
 */
@Configuration
public class CoreServiceConfig {

    // ---- ObjectMapper ----

    @Bean
    ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    // ---- Secrets ----

    @Bean
    SecretsService secretsService(SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper) {
        return new SecretsServiceImpl(secretsManagerClient, objectMapper);
    }

    // ---- Repositories ----

    @Bean
    TicketStateRepository ticketStateRepository(DynamoDbClient dynamoDbClient, AppProperties properties) {
        return new TicketStateRepository(dynamoDbClient, properties.aws().dynamodb().stateTable());
    }

    @Bean
    ConversationRepository conversationRepository(DynamoDbClient dynamoDbClient, AppProperties properties) {
        return new DynamoConversationRepository(dynamoDbClient, properties.aws().dynamodb().stateTable());
    }

    // ---- Services ----

    @Bean
    IdempotencyService idempotencyService(TicketStateRepository ticketStateRepository) {
        return new IdempotencyService(ticketStateRepository);
    }

    @Bean
    AuditService auditService(S3Client s3Client, ObjectMapper objectMapper, AppProperties properties) {
        String auditBucket = properties.aws().s3().auditBucket();
        return new AuditService(s3Client, objectMapper, auditBucket);
    }

    // ---- Budget & Rate Limiting ----

    @Bean
    BudgetTracker budgetTracker(AppProperties properties) {
        CloudWatchClient cloudWatchClient = CloudWatchClient.create();
        return new BudgetTracker(cloudWatchClient, properties.cost().monthlyBudgetUsd());
    }

    @Bean
    RateLimiter rateLimiter(DynamoDbClient dynamoDbClient, AppProperties properties) {
        return new DynamoDbRateLimiter(dynamoDbClient, properties.aws().dynamodb().stateTable());
    }

    // ---- Observability ----

    @Bean
    ObservabilityClient observabilityClient() {
        CloudWatchLogsClient logsClient = CloudWatchLogsClient.create();
        return new CloudWatchObservabilityClient(logsClient);
    }

    @Bean
    ObservabilityToolProvider observabilityToolProvider(ObservabilityClient observabilityClient) {
        return new ObservabilityToolProvider(observabilityClient);
    }
}
