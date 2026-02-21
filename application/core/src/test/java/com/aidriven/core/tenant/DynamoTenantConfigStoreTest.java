package com.aidriven.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamoTenantConfigStoreTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    private DynamoTenantConfigStore store;

    @BeforeEach
    void setUp() {
        store = new DynamoTenantConfigStore(dynamoDbClient, "test-table");
    }

    @Test
    void should_get_tenant_config() {
        String configJson = "{\"tenantId\":\"t1\"}";
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "PK", AttributeValue.fromS("TENANT#t1"),
                                "SK", AttributeValue.fromS("CONFIG"),
                                "configJson", AttributeValue.fromS(configJson)))
                        .build());

        Optional<String> result = store.getTenantConfig("t1");

        assertTrue(result.isPresent());
        assertEquals(configJson, result.get());
    }

    @Test
    void should_return_empty_when_not_found() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        Optional<String> result = store.getTenantConfig("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void should_return_empty_on_error() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenThrow(DynamoDbException.builder().message("timeout").build());

        Optional<String> result = store.getTenantConfig("t1");

        assertTrue(result.isEmpty());
    }

    @Test
    void should_save_tenant_config() {
        store.saveTenantConfig("t1", "{\"key\":\"value\"}");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(captor.capture());

        PutItemRequest request = captor.getValue();
        assertEquals("test-table", request.tableName());
        assertEquals("TENANT#t1", request.item().get("PK").s());
        assertEquals("CONFIG", request.item().get("SK").s());
        assertEquals("{\"key\":\"value\"}", request.item().get("configJson").s());
    }

    @Test
    void should_delete_tenant_config() {
        store.deleteTenantConfig("t1");

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDbClient).deleteItem(captor.capture());

        DeleteItemRequest request = captor.getValue();
        assertEquals("TENANT#t1", request.key().get("PK").s());
        assertEquals("CONFIG", request.key().get("SK").s());
    }
}