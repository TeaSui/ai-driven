package com.aidriven.lambda;

import com.aidriven.claude.ClaudeClient;
import com.aidriven.core.config.AppConfig;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.tool.context.ContextService;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentEndToEndTest {

        @Mock
        private ServiceFactory serviceFactory;
        @Mock
        private AppConfig appConfig;
        @Mock
        private JiraClient jiraClient;
        @Mock
        private SqsClient sqsClient;
        @Mock
        private ClaudeClient claudeClient;
        @Mock
        private IdempotencyService idempotencyService;
        @Mock
        private com.aidriven.core.agent.ConversationWindowManager windowManager;
        @Mock
        private com.aidriven.core.repository.TicketStateRepository ticketStateRepository;
        @Mock
        private com.aidriven.core.agent.ConversationRepository conversationRepository;
        @Mock
        private ContextService contextService;
        @Mock
        private com.aidriven.bitbucket.BitbucketClient bitbucketClient;
        @Mock
        private Context context;

        private ObjectMapper objectMapper = new ObjectMapper();

        @Test
        void test_full_agent_flow() throws Exception {
                // 1. Setup Static Mocks for Singleton Access
                try (MockedStatic<ServiceFactory> factoryMock = mockStatic(ServiceFactory.class);
                                MockedStatic<AppConfig> configMock = mockStatic(AppConfig.class)) {

                        factoryMock.when(ServiceFactory::getInstance).thenReturn(serviceFactory);
                        configMock.when(AppConfig::getInstance).thenReturn(appConfig);

                        // 2. Configure ServiceFactory to return our mocks
                        when(serviceFactory.getObjectMapper()).thenReturn(objectMapper);
                        when(serviceFactory.getJiraClient()).thenReturn(jiraClient);
                        when(serviceFactory.getSqsClient()).thenReturn(sqsClient);
                        when(serviceFactory.getClaudeClient()).thenReturn(claudeClient);
                        when(serviceFactory.getAppConfig()).thenReturn(appConfig);

                        // Return mocks for these services to avoid NPE in real methods
                        when(serviceFactory.getIdempotencyService()).thenReturn(idempotencyService);
                        when(serviceFactory.getConversationWindowManager()).thenReturn(windowManager);
                        when(serviceFactory.getBitbucketClient(any(), any())).thenReturn(bitbucketClient);
                        when(serviceFactory.createContextService(any())).thenReturn(contextService);
                        when(contextService.buildContext(any(), any())).thenReturn("Sample Code Context");

                        // Mock IdempotencyService behavior (return true = NEW event recordable)
                        when(idempotencyService.checkAndRecord(anyString(), anyString())).thenReturn(true);

                        // Mock WindowManager behavior (Stateful)
                        java.util.List<Map<String, Object>> conversation = new java.util.ArrayList<>();
                        conversation.add(Map.of("role", "user", "content", "are you done for write cucumber?"));

                        when(windowManager.appendAndBuild(anyString(), any())).thenAnswer(invocation -> {
                                com.aidriven.core.agent.model.ConversationMessage msg = invocation.getArgument(1);
                                // Simulate appending to the conversation list
                                Map<String, Object> block = new java.util.HashMap<>();
                                block.put("role", msg.getRole());
                                // ContentJson is a JSON string of blocks
                                block.put("content", objectMapper.readTree(msg.getContentJson()));
                                conversation.add(block);
                                return new java.util.ArrayList<>(conversation);
                        });

                        // 3. Configure AppConfig
                        // AGENT_ENABLED = true
                        // when(appConfig.getDynamoDbTableName()).thenReturn("ai-driven-table"); //
                        // Unused
                        when(appConfig.getAgentConfig()).thenReturn(new com.aidriven.core.config.AgentConfig(
                                        true, // Enabled
                                        "https://sqs.us-east-1.amazonaws.com/123/agent-queue",
                                        5, 600, "@ai", 50000, 2));
                        when(appConfig.getDefaultPlatform()).thenReturn("BITBUCKET");
                        when(appConfig.getDefaultWorkspace()).thenReturn("TEST-WS");
                        when(appConfig.getDefaultRepo()).thenReturn("TEST-REPO");

                        // 4. Mock External Service Responses

                        // Jira: Add Comment (Ack)
                        when(jiraClient.addComment(eq("CRM-86"), anyString())).thenReturn("comment-ack-123");

                        // SSQ: Send Message
                        when(sqsClient.sendMessage(any(Consumer.class)))
                                        .thenReturn(SendMessageResponse.builder().messageId("msg-123").build());

                        // Jira: Get Ticket (Processor)
                        TicketInfo ticketInfo = new TicketInfo();
                        ticketInfo.setTicketKey("CRM-86");
                        ticketInfo.setSummary("Write cucumber test");
                        ticketInfo.setDescription("We need to write Cucumber test for enhance quality code");
                        ticketInfo.setLabels(java.util.Collections.emptyList());
                        when(jiraClient.getTicket("CRM-86")).thenReturn(ticketInfo);

                        // DynamoDB: Idempotency check (return empty to signify not processed)
                        // when(dynamoDbClient.query(any(QueryRequest.class)))
                        // .thenReturn(QueryResponse.builder().items(Collections.emptyList()).build());

                        // Claude: Chat (Multi-turn simulation)
                        // Turn 1: Claude requests tool
                        // Turn 2: Claude concludes
                        // Claude: Chat (Multi-turn simulation - REAL HAPPY CASE)
                        // Turn 1: Claude requests ticket details
                        // Turn 2: Claude requests code context
                        // Turn 3: Claude concludes
                        when(claudeClient.chatWithTools(anyString(), anyList(), anyList()))
                                        .thenReturn(new com.aidriven.core.agent.AiClient.ToolUseResponse(
                                                        objectMapper.createArrayNode()
                                                                        .add(objectMapper.createObjectNode()
                                                                                        .put("type", "tool_use")
                                                                                        .put("id", "toolu_ticket_123")
                                                                                        .put("name", "issue_tracker_get_ticket")
                                                                                        .set("input", objectMapper
                                                                                                        .createObjectNode()
                                                                                                        .put("ticket_key",
                                                                                                                        "CRM-86"))),
                                                        "tool_use", 100, 50))
                                        .thenReturn(new com.aidriven.core.agent.AiClient.ToolUseResponse(
                                                        objectMapper.createArrayNode()
                                                                        .add(objectMapper.createObjectNode()
                                                                                        .put("type", "tool_use")
                                                                                        .put("id", "toolu_context_456")
                                                                                        .put("name", "code_context_get_context")
                                                                                        .set("input", objectMapper
                                                                                                        .createObjectNode()
                                                                                                        .put("ticket_key",
                                                                                                                        "CRM-86")
                                                                                                        .put("branch", "main"))),
                                                        "tool_use", 200, 80))
                                        .thenReturn(new com.aidriven.core.agent.AiClient.ToolUseResponse(
                                                        objectMapper.createArrayNode()
                                                                        .add(objectMapper.createObjectNode()
                                                                                        .put("type", "text")
                                                                                        .put("text", "I have analyzed the ticket and code context. The implementation looks correct.")),
                                                        "end_turn", 300, 100));

                        // ===========================================
                        // Step 1: Webhook Invocation
                        // ===========================================
                        AgentWebhookHandler webhookHandler = new AgentWebhookHandler();

                        Map<String, Object> webhookEvent = Map.of(
                                        "body", objectMapper.writeValueAsString(Map.of(
                                                        "issue", Map.of("key", "CRM-86"),
                                                        "comment", Map.of(
                                                                        "body", "@ai are you done for write cucumber?",
                                                                        "author",
                                                                        Map.of("displayName", "Minh Tung Nguyen")))));

                        Map<String, Object> response = webhookHandler.handleRequest(webhookEvent, context);

                        assertEquals(200, response.get("statusCode"));
                        verify(jiraClient).addComment(eq("CRM-86"), contains("Processing your request")); // Fuzzy match
                                                                                                          // Ack

                        // Capture SQS message
                        // We need to capture the ArgumentCaptor for the consumer, but simple way is to
                        // check the flow proceeded
                        verify(sqsClient).sendMessage(any(Consumer.class));

                        // ===========================================
                        // Step 2: Processor Invocation (Simulated SQS Trigger)
                        // ===========================================

                        // Manually construct the SQS message body that would have been sent
                        Map<String, String> sqsBodyMap = Map.of(
                                        "ticketKey", "CRM-86",
                                        "commentBody", "are you done for write cucumber?", // Trigger removed
                                        "commentAuthor", "Minh Tung Nguyen",
                                        "ackCommentId", "comment-ack-123");
                        String sqsBody = objectMapper.writeValueAsString(sqsBodyMap);

                        SQSEvent sqsEvent = new SQSEvent();
                        SQSEvent.SQSMessage sqsMessage = new SQSEvent.SQSMessage();
                        sqsMessage.setBody(sqsBody);
                        sqsEvent.setRecords(List.of(sqsMessage));

                        AgentProcessorHandler processorHandler = new AgentProcessorHandler();
                        processorHandler.handleRequest(sqsEvent, context);

                        // ===========================================
                        // Step 3: Verify Final Result & Fix
                        // ===========================================

                        // Verify Orchestrator ran and called Claude 3 times (loop: ticket -> context ->
                        // answer)
                        org.mockito.ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = org.mockito.ArgumentCaptor
                                        .forClass(List.class);
                        verify(claudeClient, times(3)).chatWithTools(anyString(), messagesCaptor.capture(), anyList());

                        List<List<Map<String, Object>>> allTurnsMessages = messagesCaptor.getAllValues();
                        assertEquals(3, allTurnsMessages.size(), "Should have called Claude three times");

                        // Debug print
                        for (int i = 0; i < 3; i++) {
                                System.out.println("Turn " + (i + 1) + " messages: "
                                                + objectMapper.writeValueAsString(allTurnsMessages.get(i)));
                        }

                        // Helper to find tool result ID in messages
                        Consumer<Integer> verifyIdInTurn = (turnIndex) -> {
                                List<Map<String, Object>> turnMessages = allTurnsMessages.get(turnIndex);
                                String expectedId = (turnIndex == 1) ? "toolu_ticket_123" : "toolu_context_456";
                                boolean found = false;
                                for (Map<String, Object> msg : turnMessages) {
                                        if ("user".equals(msg.get("role"))) {
                                                Object contentObj = msg.get("content");
                                                if (contentObj instanceof com.fasterxml.jackson.databind.JsonNode contentNode
                                                                && contentNode.isArray()) {
                                                        for (com.fasterxml.jackson.databind.JsonNode block : contentNode) {
                                                                if (block.has("type") && "tool_result"
                                                                                .equals(block.get("type").asText())) {
                                                                        if (expectedId.equals(block.get("tool_use_id")
                                                                                        .asText())) {
                                                                                found = true;
                                                                        }
                                                                }
                                                        }
                                                }
                                        }
                                }
                                assertTrue(found, "Should have found " + expectedId + " in turn " + (turnIndex + 1));
                        };

                        verifyIdInTurn.accept(1); // Turn 2 sends results of Turn 1
                        verifyIdInTurn.accept(2); // Turn 3 sends results of Turn 2

                        // Verify ConversationMessages were persisted with keys
                        org.mockito.ArgumentCaptor<com.aidriven.core.agent.model.ConversationMessage> msgCaptor = org.mockito.ArgumentCaptor
                                        .forClass(com.aidriven.core.agent.model.ConversationMessage.class);
                        verify(windowManager, atLeastOnce()).appendAndBuild(eq("CRM-86"), msgCaptor.capture());

                        List<com.aidriven.core.agent.model.ConversationMessage> capturedMessages = msgCaptor
                                        .getAllValues();
                        assertFalse(capturedMessages.isEmpty(), "Should have captured some messages");
                        for (com.aidriven.core.agent.model.ConversationMessage msg : capturedMessages) {
                                assertNotNull(msg.getPk(), "PK must not be null");
                                assertNotNull(msg.getSk(), "SK must not be null");
                                assertTrue(msg.getPk().startsWith("AGENT#"), "PK should start with AGENT#");
                                assertTrue(msg.getSk().startsWith("MSG#"), "SK should start with MSG#");
                        }

                        // Verify Final Comment posted to Jira:
                        // 1 addComment (ack from webhook)
                        // 1 editComment (final response updates the ack comment in-place)
                        verify(jiraClient, times(1)).addComment(eq("CRM-86"), anyString()); // 1 ack only
                        verify(jiraClient).editComment(eq("CRM-86"), eq("comment-ack-123"),
                                        contains("I have analyzed the ticket and code context"));
                }
        }
}
