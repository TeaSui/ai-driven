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
                "test-table", ticketStateRepository
        );
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
        // Verify ticket state updated to IN_REVIEW
        verify(ticketStateRepository).save(any());
    }

    @Test
    void should_update_existing_token_on_conflict() {
        Map<String, Object> input = new HashMap<>();
        input.put("token", "new-token");
        input.put("ticketId", "12345");
        input.put("ticketKey", "PROJ-123");
        input.put("prUrl", "https://bitbucket.org/workspace/repo/pull-requests/1");

        // First put fails with condition check, second succeeds
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("exists").build())
                .thenReturn(PutItemResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertTrue((Boolean) result.get("registered"));
        // Verify putItem was called twice (condition check fail + unconditional put)
        verify(dynamoDbClient, times(2)).putItem(any(PutItemRequest.class));
    }

    @Test
    void should_throw_when_token_is_null() {
        Map<String, Object> input = new HashMap<>();
        input.put("token", null);
        input.put("prUrl", "https://bitbucket.org/workspace/repo/pull-requests/1");

        assertThrows(NullPointerException.class, () -> {
            handler.handleRequest(input, lambdaContext);
        });
    }

    @Test
    void should_throw_when_prUrl_is_null_in_registration() {
        Map<String, Object> input = new HashMap<>();
        input.put("token", "test-token");
        input.put("prUrl", null);

        assertThrows(NullPointerException.class, () -> {
            handler.handleRequest(input, lambdaContext);
        });
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
                "pullrequest", Map.of(
                        "links", Map.of(
                                "html", Map.of("href", prUrl)
                        )
                )
        ));

        Map<String, Object> input = Map.of("body", webhookBody);

        // Mock DynamoDB getItem - return stored token
        Map<String, AttributeValue> storedItem = Map.of(
                "token", AttributeValue.builder().s("stored-task-token").build(),
                "ticketId", AttributeValue.builder().s("12345").build(),
                "ticketKey", AttributeValue.builder().s("PROJ-123").build()
        );
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(storedItem).build());

        when(sfnClient.sendTaskSuccess(any(SendTaskSuccessRequest.class)))
                .thenReturn(SendTaskSuccessResponse.builder().build());

        when(dynamoDbClient.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(DeleteItemResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertTrue((Boolean) result.get("processed"));
        assertEquals("PROJ-123", result.get("ticketKey"));

        // Verify Step Functions was called with correct token
        ArgumentCaptor<SendTaskSuccessRequest> captor = ArgumentCaptor.forClass(SendTaskSuccessRequest.class);
        verify(sfnClient).sendTaskSuccess(captor.capture());
        assertEquals("stored-task-token", captor.getValue().taskToken());

        // Verify token was deleted after processing
        verify(dynamoDbClient).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void should_process_direct_bitbucket_event_format() {
        Map<String, Object> input = new HashMap<>();
        input.put("pullrequest", Map.of(
                "links", Map.of(
                        "html", Map.of("href", "https://bitbucket.org/ws/repo/pull-requests/5")
                )
        ));

        Map<String, AttributeValue> storedItem = Map.of(
                "token", AttributeValue.builder().s("token-abc").build(),
                "ticketId", AttributeValue.builder().s("99999").build(),
                "ticketKey", AttributeValue.builder().s("TEST-99").build()
        );
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(storedItem).build());
        when(sfnClient.sendTaskSuccess(any(SendTaskSuccessRequest.class)))
                .thenReturn(SendTaskSuccessResponse.builder().build());
        when(dynamoDbClient.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(DeleteItemResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertTrue((Boolean) result.get("processed"));
        verify(sfnClient).sendTaskSuccess(any(SendTaskSuccessRequest.class));
    }

    @Test
    void should_return_not_processed_when_no_token_found() throws Exception {
        String prUrl = "https://bitbucket.org/workspace/repo/pull-requests/999";
        String webhookBody = objectMapper.writeValueAsString(Map.of(
                "pullrequest", Map.of(
                        "links", Map.of(
                                "html", Map.of("href", prUrl)
                        )
                )
        ));

        Map<String, Object> input = Map.of("body", webhookBody);

        // Return empty item
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertFalse((Boolean) result.get("processed"));
        assertTrue(((String) result.get("reason")).contains("No pending task token"));

        // Verify Step Functions was NOT called
        verify(sfnClient, never()).sendTaskSuccess(any(SendTaskSuccessRequest.class));
    }

    @Test
    void should_return_not_processed_when_no_pr_url_extractable() throws Exception {
        String webhookBody = objectMapper.writeValueAsString(Map.of(
                "event", "some-unrelated-event"
        ));

        Map<String, Object> input = Map.of("body", webhookBody);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertFalse((Boolean) result.get("processed"));
        assertTrue(((String) result.get("reason")).contains("Could not extract PR URL"));
    }

    // ==================== Mode Detection Tests ====================

    @Test
    void should_throw_for_unknown_invocation_mode() {
        Map<String, Object> input = Map.of("unknown", "data");

        assertThrows(IllegalArgumentException.class, () -> {
            handler.handleRequest(input, lambdaContext);
        });
    }

    @Test
    void should_detect_registration_mode_by_token_key() {
        Map<String, Object> input = new HashMap<>();
        input.put("token", "task-token");
        input.put("prUrl", "https://bitbucket.org/ws/repo/pull-requests/1");

        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertTrue((Boolean) result.get("registered"));
    }

    @Test
    void should_detect_callback_mode_by_body_key() throws Exception {
        String webhookBody = objectMapper.writeValueAsString(Map.of("prUrl", "https://example.com/pr/1"));
        Map<String, Object> input = Map.of("body", webhookBody);

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        // Should try callback mode (even if no token found)
        assertFalse((Boolean) result.get("processed"));
    }

    // ==================== URL Normalization Tests ====================

    @Test
    void should_normalize_url_with_trailing_slash() {
        Map<String, Object> input = new HashMap<>();
        input.put("token", "token-123");
        input.put("prUrl", "https://bitbucket.org/ws/repo/pull-requests/1/");
        input.put("ticketId", "111");
        input.put("ticketKey", "T-1");

        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        handler.handleRequest(input, lambdaContext);

        // Verify the PK uses normalized URL (lowercase, no trailing slash)
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(captor.capture());

        String pk = captor.getValue().item().get("PK").s();
        assertFalse(pk.endsWith("/"));
        assertTrue(pk.startsWith("TASK_TOKEN#"));
    }

    @Test
    void should_handle_callback_with_prUrl_field() throws Exception {
        String webhookBody = objectMapper.writeValueAsString(Map.of(
                "prUrl", "https://bitbucket.org/ws/repo/pull-requests/10"
        ));

        Map<String, Object> input = Map.of("body", webhookBody);

        Map<String, AttributeValue> storedItem = Map.of(
                "token", AttributeValue.builder().s("token-xyz").build(),
                "ticketId", AttributeValue.builder().s("55555").build(),
                "ticketKey", AttributeValue.builder().s("PROJ-555").build()
        );
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(storedItem).build());
        when(sfnClient.sendTaskSuccess(any(SendTaskSuccessRequest.class)))
                .thenReturn(SendTaskSuccessResponse.builder().build());
        when(dynamoDbClient.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(DeleteItemResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertTrue((Boolean) result.get("processed"));
    }
}
