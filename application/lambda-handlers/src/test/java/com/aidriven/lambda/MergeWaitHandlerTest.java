package com.aidriven.lambda;

import com.aidriven.core.repository.TicketStateRepository;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SendTaskSuccessRequest;
import software.amazon.awssdk.services.sfn.model.SendTaskSuccessResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for MergeWaitHandler covering registration mode, callback mode,
 * and error handling.
 */
class MergeWaitHandlerTest {

        @Mock
        private DynamoDbClient dynamoDbClient;

        @Mock
        private SfnClient sfnClient;

        @Mock
        private TicketStateRepository ticketStateRepository;

        @Mock
        private Context lambdaContext;

        private MergeWaitHandler handler;
        private final ObjectMapper objectMapper = new ObjectMapper();

        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);
                handler = new MergeWaitHandler(
                                objectMapper, dynamoDbClient, sfnClient,
                                "test-table", ticketStateRepository);
        }

        // ==================== Registration Mode Tests ====================

        @Test
        void should_register_task_token_successfully() {
                Map<String, Object> input = new HashMap<>();
                input.put("token", "test-task-token-123");
                input.put("ticketId", "12345");
                input.put("ticketKey", "PROJ-123");
                input.put("prUrl", "https://bitbucket.org/workspace/repo/pull-requests/1");

                when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                                .thenReturn(PutItemResponse.builder().build());

                Map<String, Object> result = handler.handleRequest(input, lambdaContext);

                assertTrue((Boolean) result.get("registered"));
                assertEquals("https://bitbucket.org/workspace/repo/pull-requests/1", result.get("prUrl"));

                // Verify DynamoDB put was called
                verify(dynamoDbClient).putItem(any(PutItemRequest.class));
        }

        @Test
        void should_handle_null_ticket_id_in_registration() {
                Map<String, Object> input = new HashMap<>();
                input.put("token", "test-token");
                input.put("ticketId", null);
                input.put("ticketKey", null);
                input.put("prUrl", "https://bitbucket.org/workspace/repo/pull-requests/1");

                when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                                .thenReturn(PutItemResponse.builder().build());

                Map<String, Object> result = handler.handleRequest(input, lambdaContext);

                assertTrue((Boolean) result.get("registered"));
                // Should NOT call ticketStateRepository since ticketId is null
                verify(ticketStateRepository, never()).save(any());
        }

        // ==================== Callback Mode Tests ====================

        @Test
        void should_process_merge_callback_from_api_gateway() throws Exception {
                String prUrl = "https://bitbucket.org/workspace/repo/pull-requests/1";
                String webhookBody = objectMapper.writeValueAsString(Map.of(
                                "eventKey", "pr:merged",
                                "pullRequest", Map.of(
                                                "links", Map.of(
                                                                "html", Map.of("href", prUrl)))));

                Map<String, Object> input = Map.of("body", webhookBody);

                // Mock DynamoDB getItem - return stored token
                Map<String, AttributeValue> storedItem = new HashMap<>();
                storedItem.put("token", AttributeValue.builder().s("stored-task-token").build());
                storedItem.put("ticketId", AttributeValue.builder().s("12345").build());
                storedItem.put("ticketKey", AttributeValue.builder().s("PROJ-123").build());

                when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                                .thenReturn(GetItemResponse.builder().item(storedItem).build());

                when(sfnClient.sendTaskSuccess(any(SendTaskSuccessRequest.class)))
                                .thenReturn(SendTaskSuccessResponse.builder().build());

                when(dynamoDbClient.deleteItem(any(DeleteItemRequest.class)))
                                .thenReturn(DeleteItemResponse.builder().build());

                Map<String, Object> result = handler.handleRequest(input, lambdaContext);

                assertTrue((Boolean) result.get("resumed"));
                assertEquals("PROJ-123", result.get("ticketKey"));

                // Verify Step Functions was called with correct token
                ArgumentCaptor<SendTaskSuccessRequest> captor = ArgumentCaptor.forClass(SendTaskSuccessRequest.class);
                verify(sfnClient).sendTaskSuccess(captor.capture());
                assertEquals("stored-task-token", captor.getValue().taskToken());

                // Verify token deleted
                verify(dynamoDbClient).deleteItem(any(DeleteItemRequest.class));
        }

        @Test
        void should_ignore_non_merge_event() throws Exception {
                String webhookBody = objectMapper.writeValueAsString(Map.of(
                                "eventKey", "pr:comment:added",
                                "pullRequest", Map.of(
                                                "links", Map.of(
                                                                "html", Map.of("href", "http://any-pr-url")))));

                Map<String, Object> input = Map.of("body", webhookBody);

                Map<String, Object> result = handler.handleRequest(input, lambdaContext);

                assertEquals("ignored", result.get("status"));
                verify(dynamoDbClient, never()).getItem(any(GetItemRequest.class));
        }
}
