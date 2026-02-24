package com.aidriven.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamoDbRateLimiterTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    private DynamoDbRateLimiter rateLimiter;
    private static final String TABLE_NAME = "test-table";

    @BeforeEach
    void setUp() {
        rateLimiter = new DynamoDbRateLimiter(dynamoDbClient, TABLE_NAME);
    }

    @Test
    void should_succeed_when_under_limit() {
        String key = "user:123";
        int limit = 10;

        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class))).thenReturn(null);

        rateLimiter.consumeOrThrow(key, limit);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());

        UpdateItemRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo(TABLE_NAME);
        assertThat(request.key().get("PK").s()).startsWith("RATELIMIT#" + key + "#");
        assertThat(request.conditionExpression()).contains("requestCount < :max");
        assertThat(request.expressionAttributeValues().get(":max").n()).isEqualTo("10");
        assertThat(request.expressionAttributeValues().get(":increment").n()).isEqualTo("1");
    }

    @Test
    void should_throw_rate_limit_exceeded_when_condition_fails() {
        String key = "user:456";
        int limit = 5;

        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().build());

        assertThatThrownBy(() -> rateLimiter.consumeOrThrow(key, limit))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("limit exceeded");

        verify(dynamoDbClient, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void should_fail_open_on_generic_dynamo_exception() {
        String key = "ticket:CRM-88";
        int limit = 100;

        // Simulate a network error or missing table
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB is unreachable"));

        // Should NOT throw an exception, it should catch and gracefully allow the
        // request to proceed (fail open)
        rateLimiter.consumeOrThrow(key, limit);

        verify(dynamoDbClient, times(1)).updateItem(any(UpdateItemRequest.class));
    }
}
