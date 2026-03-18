package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpringAiChatMemoryAdapter}.
 * Verifies the adapter bridges Spring AI's ChatMemory to the
 * appendAndBuild contract used by AgentOrchestrator.
 */
class SpringAiChatMemoryAdapterTest {

    private InMemoryConversationRepository conversationRepository;
    private SpringAiChatMemoryAdapter adapter;

    private static final String TENANT_ID = "test-tenant";
    private static final String TICKET_KEY = "ONC-100";
    private static final int MAX_MESSAGES = 10;

    @BeforeEach
    void setUp() {
        conversationRepository = new InMemoryConversationRepository();
        adapter = SpringAiChatMemoryAdapter.create(conversationRepository, MAX_MESSAGES);
    }

    // --- buildMessages ---

    @Nested
    class BuildMessages {

        @Test
        void should_return_empty_list_when_no_messages_exist() {
            List<Map<String, Object>> messages = adapter.buildMessages(TENANT_ID, TICKET_KEY);
            assertThat(messages).isEmpty();
        }

        @Test
        void should_return_messages_in_claude_api_format() {
            saveConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Hello\"}]", 30);
            saveConversationMessage(2, "assistant", "ai-agent",
                    "[{\"type\":\"text\",\"text\":\"Hi there\"}]", 40);

            List<Map<String, Object>> messages = adapter.buildMessages(TENANT_ID, TICKET_KEY);

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).get("role")).isEqualTo("user");
            assertThat(messages.get(1).get("role")).isEqualTo("assistant");
        }

        @Test
        void should_include_content_blocks_in_output() {
            saveConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Hello world\"}]", 30);

            List<Map<String, Object>> messages = adapter.buildMessages(TENANT_ID, TICKET_KEY);

            assertThat(messages).hasSize(1);
            Object content = messages.get(0).get("content");
            assertThat(content).isInstanceOf(List.class);

            @SuppressWarnings("unchecked")
            List<Map<String, String>> blocks = (List<Map<String, String>>) content;
            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0).get("type")).isEqualTo("text");
            assertThat(blocks.get(0).get("text")).isEqualTo("Hello world");
        }
    }

    // --- appendAndBuild ---

    @Nested
    class AppendAndBuild {

        @Test
        void should_save_message_and_return_updated_window() {
            ConversationMessage userMsg = buildConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Hello\"}]", 30);

            List<Map<String, Object>> messages = adapter.appendAndBuild(TENANT_ID, TICKET_KEY, userMsg);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).get("role")).isEqualTo("user");
        }

        @Test
        void should_accumulate_messages_across_calls() {
            ConversationMessage msg1 = buildConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"First\"}]", 30);
            ConversationMessage msg2 = buildConversationMessage(2, "assistant", "ai-agent",
                    "[{\"type\":\"text\",\"text\":\"Second\"}]", 40);
            ConversationMessage msg3 = buildConversationMessage(3, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Third\"}]", 30);

            adapter.appendAndBuild(TENANT_ID, TICKET_KEY, msg1);
            adapter.appendAndBuild(TENANT_ID, TICKET_KEY, msg2);
            List<Map<String, Object>> messages = adapter.appendAndBuild(TENANT_ID, TICKET_KEY, msg3);

            assertThat(messages).hasSize(3);
            assertThat(messages.get(0).get("role")).isEqualTo("user");
            assertThat(messages.get(1).get("role")).isEqualTo("assistant");
            assertThat(messages.get(2).get("role")).isEqualTo("user");
        }

        @Test
        void should_apply_message_window_pruning() {
            // Create adapter with small window (3 messages max)
            SpringAiChatMemoryAdapter smallAdapter = SpringAiChatMemoryAdapter.create(
                    conversationRepository, 3);

            // Add 5 messages
            for (int i = 1; i <= 5; i++) {
                String role = (i % 2 == 1) ? "user" : "assistant";
                String author = role.equals("user") ? "tea.nguyen" : "ai-agent";
                ConversationMessage msg = buildConversationMessage(i, role, author,
                        "[{\"type\":\"text\",\"text\":\"Message " + i + "\"}]", 30);
                smallAdapter.appendAndBuild(TENANT_ID, TICKET_KEY, msg);
            }

            // Retrieve should show at most 3 messages
            List<Map<String, Object>> messages = smallAdapter.buildMessages(TENANT_ID, TICKET_KEY);
            assertThat(messages.size()).isLessThanOrEqualTo(3);
        }

        @Test
        void should_merge_consecutive_same_role_messages() {
            // Two consecutive user messages (tool-output counted as user)
            ConversationMessage userMsg = buildConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Question\"}]", 30);
            ConversationMessage toolMsg = buildConversationMessage(2, "user", "tool-output",
                    "[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"Result\"}]", 40);

            adapter.appendAndBuild(TENANT_ID, TICKET_KEY, userMsg);
            List<Map<String, Object>> messages = adapter.appendAndBuild(TENANT_ID, TICKET_KEY, toolMsg);

            // Both are "user" role, so they should be merged into one API message
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).get("role")).isEqualTo("user");
        }
    }

    // --- clearConversation ---

    @Nested
    class ClearConversation {

        @Test
        void should_clear_all_messages() {
            ConversationMessage msg = buildConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Hello\"}]", 30);
            adapter.appendAndBuild(TENANT_ID, TICKET_KEY, msg);

            adapter.clearConversation(TENANT_ID, TICKET_KEY);

            List<Map<String, Object>> messages = adapter.buildMessages(TENANT_ID, TICKET_KEY);
            assertThat(messages).isEmpty();
        }
    }

    // --- convertToSpringMessage ---

    @Nested
    class ConvertToSpringMessage {

        @Test
        void should_convert_user_conversation_message_to_user_message() {
            ConversationMessage msg = buildConversationMessage(1, "user", "tea.nguyen",
                    "[{\"type\":\"text\",\"text\":\"Hello\"}]", 30);

            Message springMessage = adapter.convertToSpringMessage(msg);

            assertThat(springMessage).isInstanceOf(UserMessage.class);
            assertThat(springMessage.getText()).isEqualTo("Hello");
        }

        @Test
        void should_convert_assistant_conversation_message_to_assistant_message() {
            ConversationMessage msg = buildConversationMessage(1, "assistant", "ai-agent",
                    "[{\"type\":\"text\",\"text\":\"Response\"}]", 40);

            Message springMessage = adapter.convertToSpringMessage(msg);

            assertThat(springMessage).isInstanceOf(AssistantMessage.class);
            assertThat(springMessage.getText()).isEqualTo("Response");
        }

        @Test
        void should_convert_tool_output_to_user_message_with_prefix() {
            ConversationMessage msg = buildConversationMessage(1, "user", "tool-output",
                    "[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"Data\"}]", 30);

            Message springMessage = adapter.convertToSpringMessage(msg);

            assertThat(springMessage).isInstanceOf(UserMessage.class);
            assertThat(springMessage.getText()).contains("[Tool Output]");
        }
    }

    // --- toApiFormat ---

    @Nested
    class ToApiFormat {

        @Test
        void should_return_empty_list_for_null_input() {
            List<Map<String, Object>> result = adapter.toApiFormat(null);
            assertThat(result).isEmpty();
        }

        @Test
        void should_return_empty_list_for_empty_input() {
            List<Map<String, Object>> result = adapter.toApiFormat(List.of());
            assertThat(result).isEmpty();
        }

        @Test
        void should_convert_single_message() {
            List<Message> messages = List.of(new UserMessage("Hello"));
            List<Map<String, Object>> result = adapter.toApiFormat(messages);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("role")).isEqualTo("user");
        }

        @Test
        void should_merge_consecutive_user_messages() {
            List<Message> messages = List.of(
                    new UserMessage("First"),
                    new UserMessage("Second"));

            List<Map<String, Object>> result = adapter.toApiFormat(messages);

            // Should be merged into a single user message
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("role")).isEqualTo("user");
        }

        @Test
        void should_not_merge_alternating_role_messages() {
            List<Message> messages = List.of(
                    new UserMessage("Question"),
                    new AssistantMessage("Answer"));

            List<Map<String, Object>> result = adapter.toApiFormat(messages);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).get("role")).isEqualTo("user");
            assertThat(result.get(1).get("role")).isEqualTo("assistant");
        }
    }

    // --- Factory method ---

    @Test
    void should_create_adapter_with_factory_method() {
        SpringAiChatMemoryAdapter factoryAdapter = SpringAiChatMemoryAdapter.create(
                conversationRepository, 20);

        assertThat(factoryAdapter).isNotNull();
        assertThat(factoryAdapter.getChatMemory()).isNotNull();
    }

    // --- Constructor validation ---

    @Test
    void should_reject_null_chat_memory() {
        assertThatThrownBy(() -> new SpringAiChatMemoryAdapter(null,
                new DynamoChatMemoryRepository(conversationRepository)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_reject_null_dynamo_repository() {
        assertThatThrownBy(() -> SpringAiChatMemoryAdapter.create(null, 10))
                .isInstanceOf(NullPointerException.class);
    }

    // --- Helpers ---

    private void saveConversationMessage(int seq, String role, String author,
                                         String contentJson, int tokens) {
        conversationRepository.save(buildConversationMessage(seq, role, author, contentJson, tokens));
    }

    private ConversationMessage buildConversationMessage(int seq, String role, String author,
                                                         String contentJson, int tokens) {
        Instant timestamp = Instant.parse("2026-02-15T10:00:00Z").plusSeconds(seq * 60L);
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(TENANT_ID, TICKET_KEY))
                .sk(ConversationMessage.createSk(timestamp, seq))
                .role(role)
                .author(author)
                .contentJson(contentJson)
                .timestamp(timestamp)
                .tokenCount(tokens)
                .build();
    }
}
