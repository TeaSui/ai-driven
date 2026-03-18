package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DynamoChatMemoryRepository}.
 * Verifies the bridge between our DynamoDB ConversationRepository and
 * Spring AI's ChatMemoryRepository interface.
 */
class DynamoChatMemoryRepositoryTest {

    private InMemoryConversationRepository conversationRepository;
    private DynamoChatMemoryRepository chatMemoryRepository;

    private static final String TENANT_ID = "test-tenant";
    private static final String TICKET_KEY = "ONC-100";
    private static final String CONVERSATION_ID = TENANT_ID + "#" + TICKET_KEY;

    @BeforeEach
    void setUp() {
        conversationRepository = new InMemoryConversationRepository();
        chatMemoryRepository = new DynamoChatMemoryRepository(conversationRepository);
    }

    // --- Conversation ID parsing ---

    @Nested
    class ConversationIdParsing {

        @Test
        void should_parse_valid_conversation_id() {
            String[] parts = DynamoChatMemoryRepository.parseConversationId("acme#ONC-200");
            assertThat(parts).containsExactly("acme", "ONC-200");
        }

        @Test
        void should_create_conversation_id_from_parts() {
            String conversationId = DynamoChatMemoryRepository.toConversationId("acme", "ONC-200");
            assertThat(conversationId).isEqualTo("acme#ONC-200");
        }

        @Test
        void should_reject_conversation_id_without_separator() {
            assertThatThrownBy(() -> DynamoChatMemoryRepository.parseConversationId("noseparator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid conversationId format");
        }

        @Test
        void should_reject_conversation_id_with_leading_separator() {
            assertThatThrownBy(() -> DynamoChatMemoryRepository.parseConversationId("#ticket"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_conversation_id_with_trailing_separator() {
            assertThatThrownBy(() -> DynamoChatMemoryRepository.parseConversationId("tenant#"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_null_tenant_id_in_factory() {
            assertThatThrownBy(() -> DynamoChatMemoryRepository.toConversationId(null, "ONC-100"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void should_reject_null_ticket_key_in_factory() {
            assertThatThrownBy(() -> DynamoChatMemoryRepository.toConversationId("tenant", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // --- findByConversationId ---

    @Nested
    class FindByConversationId {

        @Test
        void should_return_empty_list_when_no_messages_exist() {
            List<Message> messages = chatMemoryRepository.findByConversationId(CONVERSATION_ID);
            assertThat(messages).isEmpty();
        }

        @Test
        void should_convert_user_message() {
            saveConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Hello agent\"}]", 50);

            List<Message> messages = chatMemoryRepository.findByConversationId(CONVERSATION_ID);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
            assertThat(messages.get(0).getText()).isEqualTo("Hello agent");
        }

        @Test
        void should_convert_assistant_message() {
            saveConversationMessage(1, "assistant", "ai-agent",
                    "[{\"type\":\"text\",\"text\":\"I can help with that\"}]", 80);

            List<Message> messages = chatMemoryRepository.findByConversationId(CONVERSATION_ID);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
            assertThat(messages.get(0).getText()).isEqualTo("I can help with that");
        }

        @Test
        void should_convert_assistant_message_with_tool_calls() {
            String contentJson = "[{\"type\":\"text\",\"text\":\"Let me check\"},"
                    + "{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"search_code\","
                    + "\"input\":{\"query\":\"UserService\"}}]";
            saveConversationMessage(1, "assistant", "ai-agent", contentJson, 120);

            List<Message> messages = chatMemoryRepository.findByConversationId(CONVERSATION_ID);

            assertThat(messages).hasSize(1);
            AssistantMessage assistantMsg = (AssistantMessage) messages.get(0);
            assertThat(assistantMsg.getText()).isEqualTo("Let me check");
            assertThat(assistantMsg.getToolCalls()).hasSize(1);
            assertThat(assistantMsg.getToolCalls().get(0).name()).isEqualTo("search_code");
            assertThat(assistantMsg.getToolCalls().get(0).id()).isEqualTo("tool_1");
        }

        @Test
        void should_convert_tool_output_message() {
            String contentJson = "[{\"type\":\"tool_result\",\"tool_use_id\":\"tool_1\","
                    + "\"content\":\"Found 3 results\"}]";
            saveConversationMessage(1, "user", "tool-output", contentJson, 60);

            List<Message> messages = chatMemoryRepository.findByConversationId(CONVERSATION_ID);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).isInstanceOf(ToolResponseMessage.class);
            ToolResponseMessage toolMsg = (ToolResponseMessage) messages.get(0);
            assertThat(toolMsg.getResponses()).hasSize(1);
            assertThat(toolMsg.getResponses().get(0).id()).isEqualTo("tool_1");
            assertThat(toolMsg.getResponses().get(0).responseData()).isEqualTo("Found 3 results");
        }

        @Test
        void should_preserve_message_order() {
            saveConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"First\"}]", 30);
            saveConversationMessage(2, "assistant", "ai-agent",
                    "[{\"type\":\"text\",\"text\":\"Second\"}]", 40);
            saveConversationMessage(3, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Third\"}]", 30);

            List<Message> messages = chatMemoryRepository.findByConversationId(CONVERSATION_ID);

            assertThat(messages).hasSize(3);
            assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
            assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
            assertThat(messages.get(2).getMessageType()).isEqualTo(MessageType.USER);
        }

        @Test
        void should_handle_malformed_content_json_gracefully() {
            saveConversationMessage(1, "user", "tea.nguyen", "plain text content", 30);

            List<Message> messages = chatMemoryRepository.findByConversationId(CONVERSATION_ID);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
            // Falls back to raw content
            assertThat(messages.get(0).getText()).isEqualTo("plain text content");
        }

        @Test
        void should_reject_null_conversation_id() {
            assertThatThrownBy(() -> chatMemoryRepository.findByConversationId(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // --- saveAll ---

    @Nested
    class SaveAll {

        @Test
        void should_save_spring_ai_messages_to_dynamo() {
            List<Message> messages = List.of(
                    new UserMessage("Hello"),
                    new AssistantMessage("Hi there"));

            chatMemoryRepository.saveAll(CONVERSATION_ID, messages);

            List<ConversationMessage> stored = conversationRepository.getConversation(TENANT_ID, TICKET_KEY);
            assertThat(stored).hasSize(2);
            assertThat(stored.get(0).getRole()).isEqualTo("user");
            assertThat(stored.get(1).getRole()).isEqualTo("assistant");
        }

        @Test
        void should_replace_existing_messages_on_save() {
            // Pre-populate
            saveConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Old message\"}]", 30);

            // Save new messages (should replace)
            List<Message> newMessages = List.of(new UserMessage("New message"));
            chatMemoryRepository.saveAll(CONVERSATION_ID, newMessages);

            List<ConversationMessage> stored = conversationRepository.getConversation(TENANT_ID, TICKET_KEY);
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getContentJson()).contains("New message");
        }

        @Test
        void should_set_correct_metadata_on_saved_messages() {
            chatMemoryRepository.saveAll(CONVERSATION_ID,
                    List.of(new UserMessage("Test")));

            List<ConversationMessage> stored = conversationRepository.getConversation(TENANT_ID, TICKET_KEY);
            ConversationMessage msg = stored.get(0);

            assertThat(msg.getPk()).isEqualTo(ConversationMessage.createPk(TENANT_ID, TICKET_KEY));
            assertThat(msg.getSk()).startsWith("MSG#");
            assertThat(msg.getTimestamp()).isNotNull();
            assertThat(msg.getTtl()).isGreaterThan(0);
            assertThat(msg.getTokenCount()).isGreaterThan(0);
        }

        @Test
        void should_reject_null_messages_list() {
            assertThatThrownBy(() -> chatMemoryRepository.saveAll(CONVERSATION_ID, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // --- deleteByConversationId ---

    @Nested
    class DeleteByConversationId {

        @Test
        void should_delete_all_messages_for_conversation() {
            saveConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Hello\"}]", 30);
            saveConversationMessage(2, "assistant", "ai-agent",
                    "[{\"type\":\"text\",\"text\":\"Hi\"}]", 30);

            chatMemoryRepository.deleteByConversationId(CONVERSATION_ID);

            List<ConversationMessage> remaining = conversationRepository.getConversation(TENANT_ID, TICKET_KEY);
            assertThat(remaining).isEmpty();
        }

        @Test
        void should_not_fail_when_deleting_nonexistent_conversation() {
            chatMemoryRepository.deleteByConversationId("nonexistent#TICKET-999");
            // No exception expected
        }
    }

    // --- findConversationIds ---

    @Test
    void should_return_empty_list_for_find_conversation_ids() {
        // This operation is not supported by DynamoDB single-table design
        List<String> ids = chatMemoryRepository.findConversationIds();
        assertThat(ids).isEmpty();
    }

    // --- Text extraction ---

    @Nested
    class TextExtraction {

        @Test
        void should_extract_text_from_single_block() {
            String result = DynamoChatMemoryRepository.extractTextContent(
                    "[{\"type\":\"text\",\"text\":\"Hello world\"}]");
            assertThat(result).isEqualTo("Hello world");
        }

        @Test
        void should_concatenate_multiple_text_blocks() {
            String result = DynamoChatMemoryRepository.extractTextContent(
                    "[{\"type\":\"text\",\"text\":\"First\"},{\"type\":\"text\",\"text\":\"Second\"}]");
            assertThat(result).isEqualTo("First\nSecond");
        }

        @Test
        void should_ignore_tool_use_blocks() {
            String json = "[{\"type\":\"text\",\"text\":\"Checking\"},"
                    + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\",\"input\":{}}]";
            String result = DynamoChatMemoryRepository.extractTextContent(json);
            assertThat(result).isEqualTo("Checking");
        }

        @Test
        void should_return_empty_for_null_content() {
            assertThat(DynamoChatMemoryRepository.extractTextContent(null)).isEmpty();
        }

        @Test
        void should_return_empty_for_blank_content() {
            assertThat(DynamoChatMemoryRepository.extractTextContent("  ")).isEmpty();
        }

        @Test
        void should_fallback_to_raw_string_for_invalid_json() {
            String result = DynamoChatMemoryRepository.extractTextContent("just plain text");
            assertThat(result).isEqualTo("just plain text");
        }
    }

    // --- Round-trip conversion ---

    @Nested
    class RoundTrip {

        @Test
        void should_round_trip_user_message() {
            ConversationMessage original = fromSpringMessage(new UserMessage("Hello"), 0);
            conversationRepository.save(original);

            List<Message> retrieved = chatMemoryRepository.findByConversationId(CONVERSATION_ID);
            assertThat(retrieved).hasSize(1);
            assertThat(retrieved.get(0)).isInstanceOf(UserMessage.class);
            assertThat(retrieved.get(0).getText()).isEqualTo("Hello");
        }

        @Test
        void should_round_trip_assistant_message() {
            ConversationMessage original = fromSpringMessage(new AssistantMessage("Understood"), 0);
            conversationRepository.save(original);

            List<Message> retrieved = chatMemoryRepository.findByConversationId(CONVERSATION_ID);
            assertThat(retrieved).hasSize(1);
            assertThat(retrieved.get(0)).isInstanceOf(AssistantMessage.class);
            assertThat(retrieved.get(0).getText()).isEqualTo("Understood");
        }

        private ConversationMessage fromSpringMessage(Message springMessage, int sequence) {
            return chatMemoryRepository.fromSpringAiMessage(
                    springMessage, TENANT_ID, TICKET_KEY, sequence);
        }
    }

    // --- Helpers ---

    private void saveConversationMessage(int seq, String role, String author,
                                         String contentJson, int tokens) {
        Instant timestamp = Instant.parse("2026-02-15T10:00:00Z").plusSeconds(seq * 60L);
        ConversationMessage msg = ConversationMessage.builder()
                .pk(ConversationMessage.createPk(TENANT_ID, TICKET_KEY))
                .sk(ConversationMessage.createSk(timestamp, seq))
                .role(role)
                .author(author)
                .contentJson(contentJson)
                .timestamp(timestamp)
                .tokenCount(tokens)
                .build();
        conversationRepository.save(msg);
    }
}
