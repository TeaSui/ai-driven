package com.aidriven.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CostTrackerTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    private CostTracker costTracker;
    private static final String TABLE = "test-table";
    private static final int BUDGET = 100_000;

    @BeforeEach
    void setUp() {
        costTracker = new CostTracker(dynamoDbClient, TABLE, BUDGET);
    }

    @Test
    void hasRemainingBudget_underBudget_returnsTrue() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of("totalTokens", AttributeValue.fromN("50000")))
                        .build());

        assertTrue(costTracker.hasRemainingBudget("TICKET-1"));
    }

    @Test
    void hasRemainingBudget_overBudget_returnsFalse() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of("totalTokens", AttributeValue.fromN("150000")))
                        .build());

        assertFalse(costTracker.hasRemainingBudget("TICKET-1"));
    }

    @Test
    void hasRemainingBudget_noRecord_returnsTrue() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertTrue(costTracker.hasRemainingBudget("TICKET-1"));
    }

    @Test
    void hasRemainingBudget_dynamoError_returnsTrue_nonFatal() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenThrow(DynamoDbException.builder().message("timeout").build());

        // Non-fatal: returns true to not block processing
        assertTrue(costTracker.hasRemainingBudget("TICKET-1"));
    }

    @Test
    void addTokens_sendsAtomicUpdate() {
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());

        costTracker.addTokens("TICKET-1", 5000);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());

        UpdateItemRequest request = captor.getValue();
        assertEquals(TABLE, request.tableName());
        // Verify the key contains AGENT# prefix
        assertTrue(request.key().get("PK").s().contains("AGENT#TICKET-1"));
        // Verify ADD expression for atomic increment
        assertTrue(request.updateExpression().contains("ADD"));
    }

    @Test
    void getRemainingBudget_calculatesCorrectly() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of("totalTokens", AttributeValue.fromN("30000")))
                        .build());

        assertEquals(70_000, costTracker.getRemainingBudget("TICKET-1"));
    }

    @Test
    void getRemainingBudget_overBudget_returnsZero() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of("totalTokens", AttributeValue.fromN("200000")))
                        .build());

        assertEquals(0, costTracker.getRemainingBudget("TICKET-1"));
    }
}
